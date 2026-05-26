package com.grupo12.poliglota.dto;

import lombok.Data;

/**
 * DTO de entrada para OP-4: datos que el instructor envía al corregir.
 */
@Data
public class CorreccionRequest {

    /** ID del curso cuya cola se va a desencolar. Ej: "C045" */
    private String cursoId;

    /** Puntaje asignado por el instructor (0.0 a 10.0) */
    private double puntaje;

    /** Comentario/feedback del instructor (puede estar vacío) */
    private String comentario;

    /** ID del instructor que realiza la corrección */
    private String instructorId;
}
