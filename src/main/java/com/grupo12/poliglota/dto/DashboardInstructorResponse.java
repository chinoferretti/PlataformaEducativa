package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;
import java.util.Set;

// DTO de respuesta para OP-3: Dashboard del Instructor.

@Data
@Builder
public class DashboardInstructorResponse {
    private String cursoId;
    private String nombreCurso;
    private String descripcion;
    private String instructorId;
    private int totalInscriptos;

    private Long alumnosActivosAhora;
    private Set<String> idsAlumnosActivos;

    private List<String> top10Ranking;
    private Long entregasPendientesCorreccion;
    private List<Map<String, Object>> inscriptosConProgreso;
}
