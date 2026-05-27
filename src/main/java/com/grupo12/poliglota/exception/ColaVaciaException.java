package com.grupo12.poliglota.exception;

public class ColaVaciaException extends RuntimeException { // Excepción personalizada para indicar que la cola de corrección está vacía cuando el instructor intenta corregir una entrega
    public ColaVaciaException(String message) {
        super(message);
    }
}
