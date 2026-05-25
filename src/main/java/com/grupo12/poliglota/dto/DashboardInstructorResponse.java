package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Set;

/**
 * DTO de respuesta para OP-3: Dashboard del Instructor.
 * Combina datos de MongoDB (info del curso) y Redis (estado en tiempo real).
 */
@Data
@Builder
public class DashboardInstructorResponse {

    // ── Datos del curso (MongoDB) ──────────────────────
    private String cursoId;
    private String nombreCurso;
    private String descripcion;
    private String instructorId;
    private int totalInscriptos;

    // ── Estado en tiempo real (Redis) ──────────────────
    private Long alumnosActivosAhora;
    private Set<String> idsAlumnosActivos;

    private List<String> top10Ranking;         // IDs de alumnos ordenados por puntaje
    private Long entregasPendientesCorreccion; // tamaño de la cola LIST
}
