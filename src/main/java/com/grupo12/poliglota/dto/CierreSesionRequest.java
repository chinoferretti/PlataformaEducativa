package com.grupo12.poliglota.dto;

import lombok.Data;

/**
 * DTO de entrada para OP-2: cierre de sesión y persistencia de progreso.
 * El estado base (posición, tiempo, respuestas_parciales) se lee del HASH de Redis;
 * acá solo viene la información que el cliente agrega al cerrar.
 */
@Data
public class CierreSesionRequest {

    /** ID del alumno. */
    private String alumnoId;

    /** ID del curso. */
    private String cursoId;

    /**
     * Estado final del módulo al cerrar: "completado" o "en_progreso".
     * Solo si es "completado" + hay puntaje, se actualiza el ranking en Redis.
     */
    private String estadoModulo;

    /** Puntaje obtenido en el módulo (si aplica). 0.0 si no era evaluación. */
    private Double puntajeObtenido;
}
