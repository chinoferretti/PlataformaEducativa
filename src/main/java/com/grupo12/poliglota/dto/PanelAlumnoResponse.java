package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO de respuesta para OP-1: Panel del alumno en cursado activo.
 * Combina datos de MongoDB (historial), Neo4j (grafo de prerequisitos)
 * y Redis (estado en tiempo real).
 */
@Data
@Builder
public class PanelAlumnoResponse {

    // ── Identificación ─────────────────────────────────
    private String alumnoId;
    private String cursoId;
    private String nombreCurso;

    // ── MongoDB: inscripción + historial ───────────────
    private String estadoInscripcion;
    private double porcentajeProgreso;
    private List<Map<String, Object>> modulosCompletados;
    private List<Double> puntajesEvaluacionesPrevias;

    // ── Neo4j: grafo ───────────────────────────────────
    private List<Map<String, Object>> cursosQueSeHabilitan;
    private String certificacionObjetivo;   // null si no se pidió
    private Integer pasosACertificacion;    // null si no se pidió, -1 si la ruta no existe

    // ── Redis: estado en tiempo real ───────────────────
    private Map<Object, Object> sesionActiva;   // null si no hay sesión viva
    private Long posicionRanking;               // null si el alumno no está en el ranking
}
