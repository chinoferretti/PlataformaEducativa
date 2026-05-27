package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.RecomendacionResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
 * OP-5: Recomendación de próximo curso (3 motores).
 *
 *   1. Neo4j   → cursos desbloqueados: prerrequisitos completados, aún no cursados
 *   2. MongoDB → detalles de cada curso + filtros opcionales por idioma y modalidad
 *   3. Redis   → contexto en tiempo real: alumnos activos (SET) y puntaje máximo (SORTED SET)
 */

@Service
@RequiredArgsConstructor
public class OP5_RecomendacionCursoService {

    private final Neo4jService neo4jService;
    private final MongoService mongoService;
    private final RedisService redisService;

    public RecomendacionResponse recomendar(String alumnoId, String idioma, String modalidad) {
        validarAlumnoId(alumnoId);

        // 1. NEO4J: cursos desbloqueados
        List<Map<String, Object>> desbloqueados = neo4jService.cursosDesbloqueadosParaAlumno(alumnoId);
        int totalDesbloqueados = desbloqueados.size();

        if (desbloqueados.isEmpty()) {
            return RecomendacionResponse.builder()
                    .alumnoId(alumnoId)
                    .totalCursosDesbloqueados(0)
                    .filtroIdioma(idioma)
                    .filtroModalidad(modalidad)
                    .recomendaciones(List.of())
                    .build();
        }

        List<ObjectId> objectIds = desbloqueados.stream()
                .map(c -> (String) c.get("id"))
                .filter(id -> id != null && ObjectId.isValid(id))
                .map(ObjectId::new)
                .collect(Collectors.toList());

        // 2. MONGODB: detalles y filtros opcionales
        List<Document> cursosMongo =
                mongoService.filtrarCursosPorIdiomaYModalidad(objectIds, idioma, modalidad);

        // 3. REDIS + ENSAMBLADO
        List<Map<String, Object>> recomendaciones = new ArrayList<>();
        for (Document curso : cursosMongo) {
            String cursoId      = curso.getObjectId("_id").toHexString();
            Long alumnosActivos = redisService.cantidadActivos(cursoId);
            Double puntajeMax   = redisService.getPuntajeMaximo(cursoId);

            Map<String, Object> item = new HashMap<>();
            item.put("cursoId",              cursoId);
            item.put("nombre",               curso.getString("nombre"));
            item.put("descripcion",          curso.getString("descripcion"));
            item.put("idioma",               curso.getString("idioma"));
            item.put("modalidad",            curso.getString("modalidad"));
            item.put("nivel",                curso.getString("nivel"));
            item.put("alumnosActivosAhora",  alumnosActivos != null ? alumnosActivos : 0L);
            item.put("puntajeMaximoRanking", puntajeMax);
            recomendaciones.add(item);
        }

        // Ordenar por puntajeMaximoRanking descendente; null va al final
        recomendaciones.sort((a, b) -> {
            Double pA = (Double) a.get("puntajeMaximoRanking");
            Double pB = (Double) b.get("puntajeMaximoRanking");
            if (pA == null && pB == null) return 0;
            if (pA == null) return 1;
            if (pB == null) return -1;
            return Double.compare(pB, pA);
        });

        return RecomendacionResponse.builder()
                .alumnoId(alumnoId)
                .totalCursosDesbloqueados(totalDesbloqueados)
                .filtroIdioma(idioma)
                .filtroModalidad(modalidad)
                .recomendaciones(recomendaciones)
                .build();
    }

    private static void validarAlumnoId(String alumnoId) {
        if (alumnoId == null || alumnoId.isBlank())
            throw new IllegalArgumentException("El campo alumnoId es obligatorio.");
        if (!ObjectId.isValid(alumnoId))
            throw new IllegalArgumentException("alumnoId no es un ObjectId válido: " + alumnoId);
    }
}
