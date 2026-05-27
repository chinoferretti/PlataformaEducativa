package com.grupo12.poliglota.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grupo12.poliglota.dto.CorreccionRequest;
import com.grupo12.poliglota.dto.CorreccionResponse;
import com.grupo12.poliglota.exception.ColaVaciaException;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Map;

/*
 * OP-4: Corrección de Evaluación (2 motores).
 *
 *   1. Redis  → desencola el siguiente trabajo de la cola LIST (RPOP)
 *   2. MongoDB → persiste el resultado en la colección "evaluaciones"
 *   3. Redis  → actualiza puntaje del alumno en el Sorted Set de ranking
 */

@Service
@RequiredArgsConstructor
public class OP4_CorreccionEvaluacionService {

    private final RedisService redisService;
    private final MongoService mongoService;
    private final ObjectMapper objectMapper;

    public CorreccionResponse corregirSiguiente(CorreccionRequest request) {

        String cursoId = request.getCursoId();

        // 1. REDIS: desencolar el siguiente trabajo
        String trabajoJson = redisService.desencolarSiguiente(cursoId);
        if (trabajoJson == null) {
            throw new ColaVaciaException("La cola de corrección está vacía para el curso: " + cursoId);
        }

        // 2. Extraer alumnoId del JSON del trabajo
        String alumnoId = extraerAlumnoId(trabajoJson);

        // 3. MONGODB: persistir resultado de la corrección
        Document saved = mongoService.persistirCorreccion(
            alumnoId, cursoId, request.getInstructorId(),
            request.getPuntaje(), request.getComentario(), trabajoJson
        );
        String mongoId = saved.getObjectId("_id").toHexString();

        // 4. REDIS: actualizar ranking del alumno
        redisService.actualizarPuntaje(cursoId, alumnoId, request.getPuntaje());
        Long nuevaPosicion = redisService.getPosicionAlumno(cursoId, alumnoId);

        Long restantes = redisService.cantidadPendientes(cursoId);

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

    private String extraerAlumnoId(String trabajoJson) {
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
