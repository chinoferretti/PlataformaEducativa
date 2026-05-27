package com.grupo12.poliglota.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorResponse { // Clase que representa la estructura de la respuesta de error para las APIs. Se puede usar para enviar mensajes de error consistentes al cliente.
    private int status;
    private String error;
    private String message;
    private String timestamp;
}
