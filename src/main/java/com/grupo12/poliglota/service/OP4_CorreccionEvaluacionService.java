package com.grupo12.poliglota.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grupo12.poliglota.dto.CorreccionRequest;
import com.grupo12.poliglota.dto.CorreccionResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/*
 * OP-4: Corrección de Evaluación
 *
 * Motores usados:
 *   - Redis    → fuente del trabajo a corregir (cola LIST) y actualización del ranking (Sorted Set)
 *   - MongoDB  → persistencia histórica del resultado de la corrección
 *
 * Justificación de la combinación:
 *   Redis actúa como cola de trabajo en tiempo real: garantiza orden FIFO y
 *   latencia mínima al desencolar. MongoDB almacena el resultado final de forma
 *   durable y consultable a largo plazo (historial de evaluaciones del alumno).
 *
 * Flujo de la operación:
 *   1. Redis  → desencola el siguiente trabajo de la cola LIST "cola:correccion:{cursoId}"
 *   2. Parsea el JSON del trabajo para extraer el alumnoId
 *   3. MongoDB → guarda el resultado de la corrección en la colección "evaluaciones"
 *   4. Redis  → actualiza el puntaje del alumno en el Sorted Set "ranking:{cursoId}"
 *   5. Retorna el resumen de lo que se hizo
 */

@Service
@RequiredArgsConstructor
public class OP4_CorreccionEvaluacionService {

    private final RedisService redisService;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public CorreccionResponse corregirSiguiente(CorreccionRequest request) { // Corrige la siguiente evaluación pendiente en la cola para el curso indicado en el request, asignándole el puntaje y comentario del instructor. Retorna un resumen de la corre

        String cursoId = request.getCursoId();

        // PASO 1: REDIS → Desencolar el siguiente trabajo 
        String trabajoJson = redisService.desencolarSiguiente(cursoId);

        if (trabajoJson == null) {
            throw new IllegalStateException("La cola de corrección está vacía para el curso: " + cursoId);
        }

        // PASO 2: Extraer alumnoId del JSON del trabajo
        String alumnoId = extraerAlumnoId(trabajoJson);

        // PASO 3: MONGODB → Persistir resultado de la corrección
        Document evaluacionDoc = new Document()
                .append("alumno_id",    alumnoId)
                .append("curso_id",     cursoId)
                .append("instructor_id", request.getInstructorId())
                .append("puntaje",      request.getPuntaje())
                .append("comentario",   request.getComentario())
                .append("trabajo_json", trabajoJson)
                .append("fecha_correccion", Instant.now().toString())
                .append("estado",       "corregido");

        Document saved = mongoTemplate.insert(evaluacionDoc, "evaluaciones");
        String mongoId = saved.getObjectId("_id").toHexString();

        // PASO 4: REDIS → Actualizar ranking del alumno 
        redisService.actualizarPuntaje(cursoId, alumnoId, request.getPuntaje());
        Long nuevaPosicion = redisService.getPosicionAlumno(cursoId, alumnoId);

        // PASO 5: Consultar cuántas entregas quedan en la cola 
        Long restantes = redisService.cantidadPendientes(cursoId);

        // PASO 6: Armar respuesta 
        return CorreccionResponse.builder()
                .alumnoId(alumnoId)
                .cursoId(cursoId)
                .trabajoOriginalJson(trabajoJson)
                .puntajeAsignado(request.getPuntaje())
                .comentario(request.getComentario())
                .instructorId(request.getInstructorId())
                .mongoDocumentId(mongoId)
                .nuevaPosicionRanking(nuevaPosicion)
                .entregasRestantesEnCola(restantes != null ? restantes : 0L)
                .build();
    }

    private String extraerAlumnoId(String trabajoJson) { // Parsea el JSON del trabajo para extraer el campo "alumno_id". Si no se encuentra o hay error, devuelve "desconocido".
        try {
            Map<String, Object> mapa = objectMapper.readValue(
                trabajoJson, new TypeReference<Map<String, Object>>() {}
            );
            Object id = mapa.get("alumno_id");
            return id != null ? id.toString() : "desconocido";
        } catch (Exception e) {
            return "desconocido";
        }
    }
}
