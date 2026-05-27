package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.PanelAlumnoResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OP-1: Panel de alumno en cursado activo (3 motores).
 *
 *   - MongoDB  → inscripción, módulos completados, puntajes históricos
 *   - Neo4j    → cursos que se habilitan si aprueba este, pasos a certificación objetivo
 *   - Redis    → estado de sesión activa (HASH) + posición en ranking (SORTED SET)
 *
 * Si no hay sesión activa en Redis, los campos {@code sesionActiva} y
 * {@code posicionRanking} pueden venir en null pero el resto se arma igual.
 * Si existe sesión activa, se refresca el TTL (consultar el panel cuenta como actividad).
 */
@Service
@RequiredArgsConstructor
public class OP1_PanelAlumnoService {

    private final MongoTemplate mongoTemplate;
    private final RedisService redisService;
    private final Neo4jService neo4jService;

    public PanelAlumnoResponse obtenerPanel(String alumnoId, String cursoId, String certObjetivo) {

        // ── 1. MongoDB: inscripción ────────────────────────────────────────
        ObjectId alumnoOid = toObjectId(alumnoId, "alumnoId");
        ObjectId cursoOid  = toObjectId(cursoId, "cursoId");

        Query inscQuery = new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid)
        ));
        Document inscripcion = mongoTemplate.findOne(inscQuery, Document.class, "inscripciones");
        if (inscripcion == null) {
            throw new IllegalArgumentException(
                "No existe inscripción para alumno " + alumnoId + " en curso " + cursoId);
        }

        String estado = inscripcion.getString("estado");
        double porcentaje = inscripcion.get("porcentaje_progreso") instanceof Number n
                ? n.doubleValue() : 0.0;

        // Nombre del curso
        Document curso = mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(cursoOid)), Document.class, "cursos");
        String nombreCurso = curso != null ? curso.getString("nombre") : "(desconocido)";

        // ── 2. MongoDB: progreso de módulos ────────────────────────────────
        Query progresoQuery = new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid)
        ));
        List<Document> progresos = mongoTemplate.find(progresoQuery, Document.class, "progreso_modulos");

        List<Map<String, Object>> modulosCompletados = new ArrayList<>();
        List<Double> puntajes = new ArrayList<>();
        for (Document p : progresos) {
            if ("completado".equalsIgnoreCase(p.getString("estado"))) {
                Map<String, Object> m = new HashMap<>();
                String nombre = p.getString("nombre_modulo");
                m.put("nombre", nombre != null ? nombre : "(sin nombre)");
                m.put("orden", p.getInteger("orden_modulo", 0));
                m.put("intentos", p.getInteger("intentos", 0));
                modulosCompletados.add(m);
            }
            Object pts = p.get("puntaje_obtenido");
            if (pts instanceof Number nm && nm.doubleValue() > 0) {
                puntajes.add(nm.doubleValue());
            }
        }

        // ── 3. Neo4j: grafo ────────────────────────────────────────────────
        List<Map<String, Object>> habilitados = neo4jService.cursosHabilitadosSiAprueba(cursoId);

        Integer pasos = null;
        if (certObjetivo != null && !certObjetivo.isBlank()) {
            int p = neo4jService.pasosACertificacion(alumnoId, certObjetivo);
            pasos = p; // -1 si la ruta no existe (se documenta así)
        }

        // ── 4. Redis: sesión activa + ranking ──────────────────────────────
        Map<Object, Object> sesion = null;
        if (redisService.existeSesion(alumnoId, cursoId)) {
            sesion = redisService.obtenerSesion(alumnoId, cursoId);
            redisService.refrescarTTLSesion(alumnoId, cursoId);
        }
        Long posRanking = redisService.getPosicionAlumno(cursoId, alumnoId);

        // ── 5. Ensamblado ──────────────────────────────────────────────────
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
