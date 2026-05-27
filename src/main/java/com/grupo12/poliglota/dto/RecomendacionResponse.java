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
    private String alumnoId;
    private int totalCursosDesbloqueados;

    private String filtroIdioma;
    private String filtroModalidad;

    private List<Map<String, Object>> recomendaciones;
}
