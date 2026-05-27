package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO de respuesta para OP-5: Recomendación de próximo curso.
 * Combina datos de Neo4j (cursos desbloqueados), MongoDB (detalles del curso)
 * y Redis (estado en tiempo real por curso).
 */
@Data
@Builder
public class RecomendacionResponse {

    // ── Identificación ─────────────────────────────────
    private String alumnoId;

    // ── Resumen ────────────────────────────────────────
    // Cantidad total de cursos que el alumno tiene habilitados (antes de filtros opcionales)
    private int totalCursosDesbloqueados;

    // Filtros aplicados (null si no se enviaron)
    private String filtroIdioma;
    private String filtroModalidad;

    // ── Recomendaciones ────────────────────────────────
    // Cada elemento del mapa tiene: cursoId, nombre, descripcion, idioma,
    // modalidad, nivel (MongoDB) + alumnosActivosAhora, puntajeMaximoRanking (Redis)
    private List<Map<String, Object>> recomendaciones;
}
