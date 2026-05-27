package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.PanelAlumnoResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * OP-1: Panel de alumno en cursado activo (3 motores).
 *
 *   - MongoDB  → inscripción, módulos completados, puntajes históricos
 *   - Neo4j    → cursos que se habilitan si aprueba este, pasos a certificación objetivo
 *   - Redis    → estado de sesión activa (HASH) + posición en ranking (SORTED SET)
 */

@Service
@RequiredArgsConstructor
public class OP1_PanelAlumnoService {

    private final MongoService mongoService;
    private final RedisService redisService;
    private final Neo4jService neo4jService;

    public PanelAlumnoResponse obtenerPanel(String alumnoId, String cursoId, String certObjetivo) {

        ObjectId alumnoOid = toObjectId(alumnoId, "alumnoId");
        ObjectId cursoOid  = toObjectId(cursoId,  "cursoId");

        // 1. MongoDB: inscripción
        Document inscripcion = mongoService.obtenerInscripcion(alumnoOid, cursoOid);
        if (inscripcion == null) {
            throw new IllegalStateException(
                "No existe inscripción para alumno " + alumnoId + " en curso " + cursoId);
        }

        String estado     = inscripcion.getString("estado");
        double porcentaje = inscripcion.get("porcentaje_progreso") instanceof Number n
                ? n.doubleValue() : 0.0;

        Document curso = mongoService.obtenerCurso(cursoOid);
        String nombreCurso = curso != null ? curso.getString("nombre") : "(desconocido)";

        // 2. MongoDB: progreso de módulos
        List<Document> progresos = mongoService.obtenerHistorialModulos(alumnoOid, cursoOid);

        List<Map<String, Object>> modulosCompletados = new ArrayList<>();
        List<Double> puntajes = new ArrayList<>();
        for (Document p : progresos) {
            if ("completado".equalsIgnoreCase(p.getString("estado"))) {
                Map<String, Object> m = new HashMap<>();
                String nombre = p.getString("nombre_modulo");
                m.put("nombre",   nombre != null ? nombre : "(sin nombre)");
                m.put("orden",    p.getInteger("orden_modulo", 0));
                m.put("intentos", p.getInteger("intentos", 0));
                modulosCompletados.add(m);
            }
            Object pts = p.get("puntaje_obtenido");
            if (pts instanceof Number nm && nm.doubleValue() > 0) {
                puntajes.add(nm.doubleValue());
            }
        }

        // 3. Neo4j
        List<Map<String, Object>> habilitados = neo4jService.cursosHabilitadosSiAprueba(cursoId);
        Integer pasos = null;
        if (certObjetivo != null && !certObjetivo.isBlank()) {
            pasos = neo4jService.pasosACertificacion(alumnoId, certObjetivo);
        }

        // 4. Redis
        Map<Object, Object> sesion = null;
        if (redisService.existeSesion(alumnoId, cursoId)) {
            sesion = redisService.obtenerSesion(alumnoId, cursoId);
            redisService.refrescarTTLSesion(alumnoId, cursoId);
        }
        Long posRanking = redisService.getPosicionAlumno(cursoId, alumnoId);

        return PanelAlumnoResponse.builder()
                .alumnoId(alumnoId)
                .cursoId(cursoId)
                .nombreCurso(nombreCurso)
                .estadoInscripcion(estado)
                .porcentajeProgreso(porcentaje)
                .modulosCompletados(modulosCompletados)
                .puntajesEvaluacionesPrevias(puntajes)
                .cursosQueSeHabilitan(habilitados)
                .certificacionObjetivo(certObjetivo)
                .pasosACertificacion(pasos)
                .sesionActiva(sesion)
                .posicionRanking(posRanking)
                .build();
    }

    private static ObjectId toObjectId(String hex, String campo) {
        if (hex == null || !ObjectId.isValid(hex)) {
            throw new IllegalArgumentException(campo + " no es un ObjectId válido: " + hex);
        }
        return new ObjectId(hex);
    }
}
