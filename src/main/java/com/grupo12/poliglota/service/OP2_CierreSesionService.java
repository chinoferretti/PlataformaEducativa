package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.CierreSesionRequest;
import com.grupo12.poliglota.dto.CierreSesionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
 *   2. MongoDB  → upsert idempotente en progreso_modulos + actualiza
 *                 porcentaje en inscripciones
 *   3. Redis    → si era evaluación con puntaje, ZADD al ranking
 *   4. Neo4j    → si el alumno completó todos los módulos, MERGE relación COMPLETO
 *   5. Redis    → DEL del HASH + SREM del SET activos
 *
 * Estrategia de coherencia ante fallos parciales:
 *   - Si MongoDB falla → 500, NO se borra el HASH (reintentable).
 *   - Si Neo4j falla   → 200 con gradoConsistencia="parcial" + aviso (reconciliable).
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class OP2_CierreSesionService {

    private final RedisService redisService;
    private final Neo4jService neo4jService;
    private final MongoTemplate mongoTemplate;

    private static final Pattern ORDEN_PATTERN = Pattern.compile("(\\d+)");

    public CierreSesionResponse cerrarSesion(CierreSesionRequest req) {

        String alumnoId = req.getAlumnoId();
        String cursoId  = req.getCursoId();
        validar(alumnoId, "alumnoId");
        validar(cursoId, "cursoId");

        // 1. REDIS: leer estado completo 
        Map<Object, Object> sesion = redisService.obtenerSesion(alumnoId, cursoId);
        if (sesion == null || sesion.isEmpty()) {
            throw new IllegalStateException(
                "No hay sesión activa en Redis para " + alumnoId + " / " + cursoId);
        }

        String moduloId = String.valueOf(sesion.getOrDefault("modulo_id", ""));
        int ordenModulo = extraerOrden(moduloId);
        String posicionSegundos = String.valueOf(sesion.getOrDefault("posicion_segundos", "0"));
        String tiempoAcumulado  = String.valueOf(sesion.getOrDefault("tiempo_acumulado", "0"));
        String respuestasParciales = String.valueOf(sesion.getOrDefault("respuestas_parciales", "{}"));

        String estadoModulo = req.getEstadoModulo() != null ? req.getEstadoModulo() : "en_progreso";
        double puntaje = req.getPuntajeObtenido() != null ? req.getPuntajeObtenido() : 0.0;

        // 2. MONGODB: persistir progreso (idempotente: upsert por alumno+curso+orden)
        ObjectId alumnoOid = new ObjectId(alumnoId);
        ObjectId cursoOid  = new ObjectId(cursoId);

        try {
            Query q = new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid),
                Criteria.where("orden_modulo").is(ordenModulo)
            ));
            Update upd = new Update()
                .set("alumno_id", alumnoOid)
                .set("curso_id", cursoOid)
                .set("orden_modulo", ordenModulo)
                .set("nombre_modulo", String.valueOf(sesion.getOrDefault("nombre_modulo", moduloId)))
                .set("estado", estadoModulo)
                .set("puntaje_obtenido", puntaje)
                .set("posicion_segundos", parseLong(posicionSegundos))
                .set("tiempo_acumulado", parseLong(tiempoAcumulado))
                .set("respuestas_parciales", respuestasParciales)
                .set("fecha_ultima_actividad", Instant.now().toString())
                .inc("intentos", 1);
            mongoTemplate.upsert(q, upd, "progreso_modulos");

            // Actualizar porcentaje en inscripciones
        } catch (RuntimeException e) { // Si falla Mongo, no cerramos la sesión en Redis para permitir reintentos. El error se eleva para que el frontend pueda mostrar un mensaje adecuado.
            log.error("OP-2: fallo Mongo al persistir progreso de {}/{}", alumnoId, cursoId, e);
            throw new RuntimeException("Error persistiendo progreso en MongoDB. " +
                    "La sesión se mantiene en Redis para reintento.", e);
        }

        // Recalcular % de progreso a partir del total de módulos del curso
        double nuevoPorcentaje = recalcularPorcentaje(alumnoOid, cursoOid);
        boolean cursoCompletado = nuevoPorcentaje >= 99.99;

        try {
            mongoTemplate.upsert(
                new Query(new Criteria().andOperator(
                    Criteria.where("alumno_id").is(alumnoOid),
                    Criteria.where("curso_id").is(cursoOid)
                )),
                new Update()
                    .set("porcentaje_progreso", nuevoPorcentaje)
                    .set("estado", cursoCompletado ? "completada" : "activa"),
                "inscripciones"
            );
        } catch (RuntimeException e) {
            log.error("OP-2: fallo Mongo al actualizar inscripción de {}/{}", alumnoId, cursoId, e);
            throw new RuntimeException("Error actualizando inscripción en MongoDB.", e);
        }

        // 3. REDIS: actualizar ranking si fue evaluación aprobada con puntaje > 0 
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
            log.warn("OP-2: fallo Neo4j para {}/{}. Mongo y Redis quedaron consistentes, requiere reconciliación.",
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

    // Recalcula % progreso = (módulos completados / total módulos del curso) * 100
    private double recalcularPorcentaje(ObjectId alumnoOid, ObjectId cursoOid) {
        Document curso = mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(cursoOid)), Document.class, "cursos");
        if (curso == null) return 0.0;

        Object modulosRaw = curso.get("modulos");
        int total = (modulosRaw instanceof List<?> lst) ? lst.size() : 0;
        if (total == 0) return 0.0;

        long completados = mongoTemplate.count(
            new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid),
                Criteria.where("estado").is("completado")
            )),
            "progreso_modulos"
        );
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
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("El campo " + campo + " es obligatorio.");
        }
        if (!ObjectId.isValid(v)) {
            throw new IllegalArgumentException(campo + " no es un ObjectId válido: " + v);
        }
    }
}
