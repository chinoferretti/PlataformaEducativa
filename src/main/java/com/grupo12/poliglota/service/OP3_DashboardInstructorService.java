package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.DashboardInstructorResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OP-3: Dashboard del Instructor
 *
 * Motores usados:
 *   - MongoDB  → datos estáticos del curso (nombre, descripción, inscriptos)
 *   - Redis    → estado en tiempo real (activos, ranking, cola de corrección)
 *
 * Justificación de la combinación:
 *   MongoDB guarda el historial y la estructura del curso (datos que no cambian
 *   cada segundo). Redis guarda el estado operativo en tiempo real con latencia
 *   sub-milisegundo, ideal para un dashboard que se refresca frecuentemente.
 */
@Service
@RequiredArgsConstructor
public class OP3_DashboardInstructorService {

    private final MongoTemplate mongoTemplate;
    private final RedisService redisService;

    /**
     * Construye el dashboard completo para un instructor dado un cursoId.
     *
     * @param cursoId identificador del curso (ej: "C045")
     * @return DashboardInstructorResponse con datos de ambos motores
     * @throws IllegalArgumentException si el curso no existe en MongoDB
     */
    public DashboardInstructorResponse obtenerDashboard(String cursoId) {

        // ── 1. CONSULTA A MONGODB ──────────────────────────────────────────
        // Busca el documento del curso en la colección "cursos"
        Query query = new Query(Criteria.where("curso_id").is(cursoId));
        Document cursoDoc = mongoTemplate.findOne(query, Document.class, "cursos");

        if (cursoDoc == null) {
            throw new IllegalArgumentException("Curso no encontrado en MongoDB: " + cursoId);
        }

        String nombreCurso  = cursoDoc.getString("nombre");
        String descripcion  = cursoDoc.getString("descripcion");
        String instructorId = cursoDoc.getString("instructor_id");

        // Cantidad de inscriptos: puede ser un campo numérico o el tamaño de una lista
        int totalInscriptos = 0;
        Object inscriptos = cursoDoc.get("inscriptos");
        if (inscriptos instanceof List<?>) {
            totalInscriptos = ((List<?>) inscriptos).size();
        } else if (inscriptos instanceof Integer) {
            totalInscriptos = (Integer) inscriptos;
        }

        // ── 2. CONSULTAS A REDIS ───────────────────────────────────────────

        // SET: alumnos conectados en este momento
        Set<String> idsActivos   = redisService.getAlumnosActivos(cursoId);
        Long cantidadActivos     = redisService.cantidadActivos(cursoId);

        // SORTED SET: top 10 del ranking
        Set<String> top10Set     = redisService.getTop10(cursoId);
        List<String> top10       = new ArrayList<>(top10Set != null ? top10Set : List.of());

        // LIST: entregas esperando corrección
        Long pendientes          = redisService.cantidadPendientes(cursoId);

        // ── 3. ARMADO DE LA RESPUESTA ──────────────────────────────────────
        return DashboardInstructorResponse.builder()
                .cursoId(cursoId)
                .nombreCurso(nombreCurso)
                .descripcion(descripcion)
                .instructorId(instructorId)
                .totalInscriptos(totalInscriptos)
                .alumnosActivosAhora(cantidadActivos)
                .idsAlumnosActivos(idsActivos)
                .top10Ranking(top10)
                .entregasPendientesCorreccion(pendientes != null ? pendientes : 0L)
                .build();
    }
}
