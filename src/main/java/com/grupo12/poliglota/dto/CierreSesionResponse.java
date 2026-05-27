package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/*
 * DTO de respuesta para OP-2.
 * gradoConsistencia indica si las 3 escrituras completaron:
 *   "total"   → Mongo + Redis + (Neo4j si aplicaba) ok
 *   "parcial" → Mongo + Redis ok, Neo4j falló (queda para reconciliación posterior)
 */
@Data
@Builder
public class CierreSesionResponse {
    private String alumnoId;
    private String cursoId;
    private String moduloIdProcesado;
    private int ordenModulo;
    private boolean cursoCompletado;
    private double porcentajeProgresoActualizado;
    private Long nuevaPosicionRanking;   // null si no se actualizó ranking
    private String gradoConsistencia;    // "total" o "parcial"
    private List<String> avisos;
}
