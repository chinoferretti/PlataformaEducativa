package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.DashboardInstructorResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * OP-3: Dashboard del Instructor (2 motores).
 *
 *   - MongoDB  → datos estáticos del curso + lista de inscriptos con progreso individual
 *   - Redis    → alumnos activos (SET), top 10 ranking (SORTED SET), cola corrección (LIST)
 */

@Service
@RequiredArgsConstructor
public class OP3_DashboardInstructorService {

    private final MongoService mongoService;
    private final RedisService redisService;

    public DashboardInstructorResponse obtenerDashboard(String cursoId) {

        if (!ObjectId.isValid(cursoId)) {
            throw new IllegalArgumentException("cursoId no es un ObjectId válido: " + cursoId);
        }
        ObjectId cursoOid = new ObjectId(cursoId);

        // 1. MONGODB: datos del curso
        Document cursoDoc = mongoService.obtenerCurso(cursoOid);
        if (cursoDoc == null) {
            throw new IllegalStateException("Curso no encontrado en MongoDB: " + cursoId);
        }

        String nombreCurso  = cursoDoc.getString("nombre");
        String descripcion  = cursoDoc.getString("descripcion");
        Object instObj      = cursoDoc.get("instructor_id");
        String instructorId = instObj != null ? instObj.toString() : null;
        int totalInscriptos = cursoDoc.getInteger("total_inscriptos", 0);

        // 2. MONGODB: lista de inscriptos con porcentaje de progreso individual
        List<Map<String, Object>> inscriptosConProgreso =
                mongoService.obtenerInscriptosConProgreso(cursoOid);

        // 3. REDIS: estado en tiempo real
        Set<String> idsActivos = redisService.getAlumnosActivos(cursoId);
        Long cantidadActivos   = redisService.cantidadActivos(cursoId);

        Set<String> top10Set   = redisService.getTop10(cursoId);
        List<String> top10     = new ArrayList<>(top10Set != null ? top10Set : List.of());

        Long pendientes        = redisService.cantidadPendientes(cursoId);

        return DashboardInstructorResponse.builder()
                .cursoId(cursoId)
                .nombreCurso(nombreCurso)
                .descripcion(descripcion)
                .instructorId(instructorId)
                .totalInscriptos(totalInscriptos)
                .inscriptosConProgreso(inscriptosConProgreso)
                .alumnosActivosAhora(cantidadActivos)
                .idsAlumnosActivos(idsActivos)
                .top10Ranking(top10)
                .entregasPendientesCorreccion(pendientes != null ? pendientes : 0L)
                .build();
    }
}
