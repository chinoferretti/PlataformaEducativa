package com.grupo12.poliglota.dto;

import lombok.Data;

// DTO de entrada para OP-2: cierre de sesión y persistencia de progreso.

@Data
public class CierreSesionRequest {
    private String alumnoId;
    private String cursoId;
    private String estadoModulo;
    private Double puntajeObtenido;
}
