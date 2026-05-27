package com.grupo12.poliglota.service;

import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Servicio Neo4j para el grafo de cursos, prerrequisitos y rutas de certificación.
 *
 * Modelo de grafo:
 *   (:Curso {id, nombre, categoria})-[:REQUIERE]->(:Curso)
 *   (:RutaCertificacion {id, nombre})-[:INCLUYE {orden}]->(:Curso)
 *   (:Alumno {id})-[:COMPLETO {fecha, puntaje}]->(:Curso)   -- on-demand desde OP-2
 */

@Service
@RequiredArgsConstructor
public class Neo4jService {

    private final Driver driver;

    // OP-1

    public List<Map<String, Object>> cursosHabilitadosSiAprueba(String cursoId) { // Devuelve los cursos que tienen al "cursoId" como prerrequisito directo
        String cypher = """
                MATCH (dependiente:Curso)-[:REQUIERE]->(c:Curso {id: $cursoId})
                RETURN dependiente.id AS id, dependiente.nombre AS nombre
                ORDER BY dependiente.nombre
                """;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Session s = driver.session()) {
            Result r = s.run(cypher, Map.of("cursoId", cursoId));
            r.forEachRemaining(rec -> out.add(rec.asMap()));
        }
        return out;
    }

    public int pasosACertificacion(String alumnoId, String certificacionId) { // Devuelve la cantidad de cursos que le faltarían al alumno para completar la ruta de certificación, asumiendo que aprueba todo lo que le falta. Retorna -1 si el alumno ya tiene la certificación o si no existe la ruta.
        String cypher = """
                MATCH (cert:RutaCertificacion {id: $certId})-[:INCLUYE]->(c:Curso)
                WITH cert, collect(c) AS cursosRuta
                OPTIONAL MATCH (a:Alumno {id: $alumnoId})-[:COMPLETO]->(c:Curso)
                WHERE c IN cursosRuta
                WITH cursosRuta, count(DISTINCT c) AS completos
                RETURN size(cursosRuta) - completos AS faltantes, size(cursosRuta) AS total
                """;
        try (Session s = driver.session()) {
            Result r = s.run(cypher, Map.of("alumnoId", alumnoId, "certId", certificacionId));
            if (!r.hasNext()) return -1;
            var rec = r.next();
            if (rec.get("total").asInt(0) == 0) return -1;
            return rec.get("faltantes").asInt();
        }
    }

    // OP-5 

    public List<Map<String, Object>> cursosDesbloqueadosParaAlumno(String alumnoId) { // Devuelve los cursos que el alumno tiene habilitados para cursar a continuación, asumiendo que aprueba lo que le falta. Solo devuelve id y nombre del curso.
        String cypher = """
                MATCH (candidato:Curso)
                WHERE NOT EXISTS { MATCH (:Alumno {id: $alumnoId})-[:COMPLETO]->(candidato) }
                WITH candidato
                OPTIONAL MATCH (candidato)-[:REQUIERE]->(req:Curso)
                WITH candidato, collect(req.id) AS idsRequeridos
                WHERE ALL(rid IN idsRequeridos WHERE EXISTS {
                    MATCH (:Alumno {id: $alumnoId})-[:COMPLETO]->(:Curso {id: rid})
                })
                RETURN candidato.id AS id, candidato.nombre AS nombre
                ORDER BY candidato.nombre
                """;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Session s = driver.session()) {
            Result r = s.run(cypher, Map.of("alumnoId", alumnoId));
            r.forEachRemaining(rec -> out.add(rec.asMap()));
        }
        return out;
    }

    // OP-2

    public void actualizarProgreso(String alumnoId, String cursoId, double porcentaje) { // Actualiza el porcentaje de progreso del alumno en el curso dentro de la relación INSCRIPTO_EN. No crea nodos ni relaciones, asume que ya existe la relación.
        String cypher = """
                MATCH (a:Alumno {id: $alumnoId})-[r:INSCRIPTO_EN]->(c:Curso {id: $cursoId})
                SET r.porcentaje_progreso = $porcentaje,
                    r.fecha_ultima_actividad = datetime()
                """;
        try (Session s = driver.session()) {
            s.run(cypher, Map.of(
                "alumnoId", alumnoId,
                "cursoId", cursoId,
                "porcentaje", porcentaje
            ));
        }
    }

    public void marcarCursoCompletado(String alumnoId, String cursoId, double puntajeFinal) { // Marca el curso como completado para el alumno, creando o actualizando la relación COMPLETO con la fecha y el puntaje final. No elimina ni modifica la relación INSCRIPTO_EN.
        String cypher = """
                MERGE (a:Alumno {id: $alumnoId})
                WITH a
                MATCH (c:Curso {id: $cursoId})
                MERGE (a)-[r:COMPLETO]->(c)
                SET r.fecha = datetime(), r.puntaje = $puntaje
                """;
        try (Session s = driver.session()) {
            s.run(cypher, Map.of(
                "alumnoId", alumnoId,
                "cursoId", cursoId,
                "puntaje", puntajeFinal
            ));
        }
    }
}
