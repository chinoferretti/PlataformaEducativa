package com.grupo12.poliglota.dto;

import lombok.Data;

// DTO de entrada para OP-4: datos que el instructor envía al corregir.

@Data
public class CorreccionRequest {
    private String cursoId;
    private double puntaje;
    private String comentario;
    private String instructorId;
}
