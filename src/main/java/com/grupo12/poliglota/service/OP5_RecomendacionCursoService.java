package com.grupo12.poliglota.service;

import com.grupo12.poliglota.dto.RecomendacionResponse;
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
import java.util.stream.Collectors;

/*
 * OP-5: Recomendación de próximo curso (3 motores).
 *
 * Flujo:
 *   1. Neo4j   → cursos desbloqueados para el alumno:
 *                todos sus prerrequisitos están completados y él no los cursó aún
 *   2. MongoDB → enriquece cada curso con sus datos (nombre, descripción, idioma, modalidad, nivel)
 *                y aplica filtros opcionales por idioma o modalidad
 *   3. Redis   → agrega contexto en tiempo real por curso: alumnos activos ahora
 *                y puntaje máximo alcanzado en el ranking
 *
 * Manejo de borde: si el alumno todavía no completó ningún curso (nodo Alumno
 * inexistente en Neo4j), se devuelven los cursos que no tienen ningún prerrequisito.
 */

@Service
@RequiredArgsConstructor
public class OP5_RecomendacionCursoService {

    private final Neo4jService neo4jService;
    private final MongoTemplate mongoTemplate;
    private final RedisService redisService;

    public RecomendacionResponse recomendar(String alumnoId, String idioma, String modalidad) { // Devuelve una lista de cursos recomendados para el alumno, enriquecidos con datos de MongoDB y Redis, y filtrados por idioma/modalidad si se especifican.
        validarAlumnoId(alumnoId);

        // 1. NEO4J: obtener IDs de cursos desbloqueados 
        List<Map<String, Object>> desbloqueados = neo4jService.cursosDesbloqueadosParaAlumno(alumnoId); // Devuelve una lista de mapas con al menos la clave "id" que es el ID del curso en MongoDB. Si el alumno no existe, devuelve los cursos sin prerrequisitos.

        int totalDesbloqueados = desbloqueados.size();

        // Si Neo4j no devolvió nada, no hay cursos para recomendar
        if (desbloqueados.isEmpty()) {
            return RecomendacionResponse.builder()
                    .alumnoId(alumnoId)
                    .totalCursosDesbloqueados(0)
                    .filtroIdioma(idioma)
                    .filtroModalidad(modalidad)
                    .recomendaciones(List.of())
                    .build();
        }

        // Extraer los IDs de curso que devolvió Neo4j
        List<String> idsCursos = desbloqueados.stream()
                .map(c -> (String) c.get("id"))
                .filter(id -> id != null && ObjectId.isValid(id))
                .collect(Collectors.toList());

        //  2. MONGODB: obtener detalles y aplicar filtros opcionales 
        List<ObjectId> objectIds = idsCursos.stream() // Convertir los strings a ObjectId para la query
                .map(ObjectId::new)
                .collect(Collectors.toList());

        // Criterio base: _id dentro del conjunto de cursos desbloqueados
        Criteria criteria = Criteria.where("_id").in(objectIds);

        // Agregar filtro de idioma solo si fue enviado
        if (idioma != null && !idioma.isBlank()) {
            criteria = criteria.and("idioma").is(idioma);
        }

        // Agregar filtro de modalidad solo si fue enviado
        if (modalidad != null && !modalidad.isBlank()) {
            criteria = criteria.and("modalidad").is(modalidad);
        }

        List<Document> cursosMongo = mongoTemplate.find(new Query(criteria), Document.class, "cursos");

        // 3. REDIS + ENSAMBLADO: enriquecer cada curso con datos en tiempo real
        List<Map<String, Object>> recomendaciones = new ArrayList<>();

        for (Document curso : cursosMongo) {
            String cursoId = curso.getObjectId("_id").toHexString();

            // Redis SET: cuántos alumnos están conectados a este curso ahora mismo
            Long alumnosActivos = redisService.cantidadActivos(cursoId);

            // Redis SORTED SET: puntaje más alto registrado en el ranking del curso
            Double puntajeMax = redisService.getPuntajeMaximo(cursoId);

            // Ensamblar el mapa con datos de ambos motores
            Map<String, Object> item = new HashMap<>();
            item.put("cursoId",               cursoId);
            item.put("nombre",                curso.getString("nombre"));
            item.put("descripcion",           curso.getString("descripcion"));
            item.put("idioma",                curso.getString("idioma"));
            item.put("modalidad",             curso.getString("modalidad"));
            item.put("nivel",                 curso.getString("nivel"));
            item.put("alumnosActivosAhora",   alumnosActivos != null ? alumnosActivos : 0L);
            item.put("puntajeMaximoRanking",  puntajeMax);   // null si nadie tiene puntaje aún

            recomendaciones.add(item);
        }

        // Ordenar por puntajeMaximoRanking descendente (cursos más competitivos primero).
        // Los cursos sin ranking todavía (null) van al final.
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

    // Valida que el alumnoId sea un ObjectId MongoDB válido (convención del proyecto)
    private static void validarAlumnoId(String alumnoId) {
        if (alumnoId == null || alumnoId.isBlank()) {
            throw new IllegalArgumentException("El campo alumnoId es obligatorio.");
        }
        if (!ObjectId.isValid(alumnoId)) {
            throw new IllegalArgumentException("alumnoId no es un ObjectId válido: " + alumnoId);
        }
    }
}
