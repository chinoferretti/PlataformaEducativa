package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;

/**
 * DTO de respuesta para OP-4: resultado de la corrección realizada.
 */
@Data
@Builder
public class CorreccionResponse {

    // ── Trabajo corregido (venía de Redis) ────────────────
    private String alumnoId;
    private String cursoId;
    private String trabajoOriginalJson;   // el JSON que estaba en la cola

    // ── Resultado de la corrección ────────────────────────
    private double puntajeAsignado;
    private String comentario;
    private String instructorId;

    // ── Estado posterior ──────────────────────────────────
    private String mongoDocumentId;       // _id del documento guardado en MongoDB
    private Long nuevaPosicionRanking;    // posición del alumno en el ranking tras actualizar
    private Long entregasRestantesEnCola; // cuántas entregas quedan en la cola
}
