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

    // ── OP-1 ──────────────────────────────────────────────────────────────

    /**
     * Devuelve los cursos que tienen al {@code cursoId} como prerrequisito directo.
     * Es decir: si el alumno aprueba este curso, qué cursos quedan "habilitados".
     */
    public List<Map<String, Object>> cursosHabilitadosSiAprueba(String cursoId) {
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

    /**
     * Cantidad de cursos de una ruta de certificación que el alumno todavía NO completó.
     * Si la ruta no existe o no tiene cursos, devuelve -1.
     */
    public int pasosACertificacion(String alumnoId, String certificacionId) {
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

    // ── OP-5 ──────────────────────────────────────────────────────────────

    /**
     * Devuelve los cursos que el alumno puede cursar a continuación:
     *   - No los completó todavía (no existe relación COMPLETO hacia ese curso)
     *   - Tiene completados TODOS sus prerrequisitos (relaciones REQUIERE satisfechas)
     *
     * Caso borde: si el alumno nunca completó un curso (su nodo Alumno no existe
     * en Neo4j), ALL() sobre lista vacía devuelve true → se retornan solo los
     * cursos sin prerrequisitos, que son los únicos disponibles para un alumno nuevo.
     */
    public List<Map<String, Object>> cursosDesbloqueadosParaAlumno(String alumnoId) {
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

    // ── OP-2 ──────────────────────────────────────────────────────────────

    /**
     * Marca un curso como completado por el alumno. Crea el nodo Alumno si no existe.
     * Esto habilita en el grafo los cursos que tenían a este como prerrequisito.
     */
    public void marcarCursoCompletado(String alumnoId, String cursoId, double puntajeFinal) {
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
