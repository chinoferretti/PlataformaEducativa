package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.CierreSesionRequest;
import com.grupo12.poliglota.dto.CierreSesionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * OP-2: Cierre de sesión y persistencia de progreso (3 motores).
 *
 * Flujo:
 *   1. Redis    → HGETALL del HASH de sesión
 *   2. MongoDB  → upsert idempotente en progreso_modulos + actualiza porcentaje en inscripciones
 *   3. Redis    → si era evaluación con puntaje, ZADD al ranking
 *   4. Neo4j    → actualiza INSCRIPTO_EN + MERGE relación COMPLETO si el curso llegó al 100%
 *   5. Redis    → DEL del HASH + SREM del SET activos
 *
 * Coherencia: si MongoDB falla → 500, HASH NO se borra (reintentable).
 *             si Neo4j falla   → 200 con gradoConsistencia="parcial" (reconciliable).
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class OP2_CierreSesionService {

    private final RedisService redisService;
    private final Neo4jService neo4jService;
    private final MongoService mongoService;

    private static final Pattern ORDEN_PATTERN = Pattern.compile("(\\d+)");

    public CierreSesionResponse cerrarSesion(CierreSesionRequest req) {

        String alumnoId = req.getAlumnoId();
        String cursoId  = req.getCursoId();
        validar(alumnoId, "alumnoId");
        validar(cursoId,  "cursoId");

        // 1. REDIS: leer estado completo
        Map<Object, Object> sesion = redisService.obtenerSesion(alumnoId, cursoId);
        if (sesion == null || sesion.isEmpty()) {
            throw new IllegalStateException(
                "No hay sesión activa en Redis para " + alumnoId + " / " + cursoId);
        }

        String moduloId         = String.valueOf(sesion.getOrDefault("modulo_id", ""));
        int    ordenModulo      = extraerOrden(moduloId);
        String nombreModulo     = String.valueOf(sesion.getOrDefault("nombre_modulo", moduloId));
        String posicionStr      = String.valueOf(sesion.getOrDefault("posicion_segundos", "0"));
        String tiempoStr        = String.valueOf(sesion.getOrDefault("tiempo_acumulado",  "0"));
        String respuestas       = String.valueOf(sesion.getOrDefault("respuestas_parciales", "{}"));

        String estadoModulo = req.getEstadoModulo()   != null ? req.getEstadoModulo()   : "en_progreso";
        double puntaje      = req.getPuntajeObtenido() != null ? req.getPuntajeObtenido() : 0.0;

        ObjectId alumnoOid = new ObjectId(alumnoId);
        ObjectId cursoOid  = new ObjectId(cursoId);

        // 2. MONGODB: persistir progreso (idempotente por alumno+curso+orden)
        try {
            mongoService.persistirProgresoModulo(
                alumnoOid, cursoOid, ordenModulo, nombreModulo,
                estadoModulo, puntaje,
                parseLong(posicionStr), parseLong(tiempoStr), respuestas
            );
        } catch (RuntimeException e) {
            log.error("OP-2: fallo Mongo al persistir progreso de {}/{}", alumnoId, cursoId, e);
            throw new RuntimeException("Error persistiendo progreso en MongoDB. " +
                    "La sesión se mantiene en Redis para reintento.", e);
        }

        double nuevoPorcentaje = recalcularPorcentaje(alumnoOid, cursoOid);
        boolean cursoCompletado = nuevoPorcentaje >= 99.99;

        try {
            mongoService.actualizarPorcentajeInscripcion(alumnoOid, cursoOid,
                    nuevoPorcentaje, cursoCompletado);
        } catch (RuntimeException e) {
            log.error("OP-2: fallo Mongo al actualizar inscripción de {}/{}", alumnoId, cursoId, e);
            throw new RuntimeException("Error actualizando inscripción en MongoDB.", e);
        }

        // 3. REDIS: actualizar ranking si fue evaluación con puntaje > 0
        Long nuevaPosicionRanking = null;
        if (puntaje > 0 && "completado".equalsIgnoreCase(estadoModulo)) {
            redisService.actualizarPuntaje(cursoId, alumnoId, puntaje);
            nuevaPosicionRanking = redisService.getPosicionAlumno(cursoId, alumnoId);
        }

        // 4. NEO4J: actualizar INSCRIPTO_EN + marcar COMPLETO si aplica
        List<String> avisos = new ArrayList<>();
        String gradoConsistencia = "total";
        try {
            neo4jService.actualizarProgreso(alumnoId, cursoId, nuevoPorcentaje);
            if (cursoCompletado) {
                neo4jService.marcarCursoCompletado(alumnoId, cursoId, puntaje);
            }
        } catch (RuntimeException e) {
            log.warn("OP-2: fallo Neo4j para {}/{}. Mongo y Redis quedaron consistentes.",
                     alumnoId, cursoId, e);
            gradoConsistencia = "parcial";
            avisos.add("No se pudo actualizar el grafo en Neo4j. " +
                       "El progreso y la habilitación de cursos dependientes quedan pendientes de reconciliación.");
        }

        // 5. REDIS: limpiar sesión y presencia
        redisService.cerrarSesion(alumnoId, cursoId);
        redisService.removerAlumnoActivo(cursoId, alumnoId);

        return CierreSesionResponse.builder()
                .alumnoId(alumnoId)
                .cursoId(cursoId)
                .moduloIdProcesado(moduloId)
                .ordenModulo(ordenModulo)
                .cursoCompletado(cursoCompletado)
                .porcentajeProgresoActualizado(nuevoPorcentaje)
                .nuevaPosicionRanking(nuevaPosicionRanking)
                .gradoConsistencia(gradoConsistencia)
                .avisos(avisos)
                .build();
    }

    private double recalcularPorcentaje(ObjectId alumnoOid, ObjectId cursoOid) {
        Document curso = mongoService.obtenerCurso(cursoOid);
        if (curso == null) return 0.0;
        Object modulosRaw = curso.get("modulos");
        int total = (modulosRaw instanceof List<?> lst) ? lst.size() : 0;
        if (total == 0) return 0.0;
        long completados = mongoService.contarModulosCompletados(alumnoOid, cursoOid);
        return Math.round((completados * 1000.0 / total)) / 10.0;
    }

    private static int extraerOrden(String moduloId) {
        if (moduloId == null) return 0;
        Matcher m = ORDEN_PATTERN.matcher(moduloId);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    private static void validar(String v, String campo) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("El campo " + campo + " es obligatorio.");
        if (!ObjectId.isValid(v))
            throw new IllegalArgumentException(campo + " no es un ObjectId válido: " + v);
    }
}
