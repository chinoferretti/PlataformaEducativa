package com.grupo12.poliglota.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Set;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, String> redisTemplate; // Inyectamos el RedisTemplate para interactuar con Redis

    // ─── SESIÓN (HASH) ───────────────────────────────────────

    public void iniciarSesion(String alumnoId, String cursoId, String moduloId) { // Creamos una clave única para la sesión del alumno en el curso
        String key = "sesion:" + alumnoId + ":" + cursoId;
        redisTemplate.opsForHash().put(key, "alumno_id", alumnoId);
        redisTemplate.opsForHash().put(key, "curso_id", cursoId);
        redisTemplate.opsForHash().put(key, "modulo_id", moduloId);
        redisTemplate.opsForHash().put(key, "posicion_segundos", "0");
        redisTemplate.opsForHash().put(key, "tiempo_acumulado", "0");
        redisTemplate.opsForHash().put(key, "ultima_actividad", String.valueOf(System.currentTimeMillis()));
        redisTemplate.expire(key, 2, TimeUnit.HOURS);
    }

    public void actualizarPosicion(String alumnoId, String cursoId, int segundos) { // Actualizamos la posición del alumno en el módulo y el tiempo acumulado
        String key = "sesion:" + alumnoId + ":" + cursoId;
        redisTemplate.opsForHash().put(key, "posicion_segundos", String.valueOf(segundos));
        redisTemplate.opsForHash().put(key, "ultima_actividad", String.valueOf(System.currentTimeMillis()));
    }

    public void guardarRespuestaParcial(String alumnoId, String cursoId, String respuestasJson) { // Guardamos las respuestas parciales del alumno en la sesión
        String key = "sesion:" + alumnoId + ":" + cursoId;
        redisTemplate.opsForHash().put(key, "respuestas_parciales", respuestasJson);
        redisTemplate.opsForHash().put(key, "ultima_actividad", String.valueOf(System.currentTimeMillis()));
    }

    public Map<Object, Object> obtenerSesion(String alumnoId, String cursoId) { // Obtenemos toda la información de la sesión del alumno para el curso
        String key = "sesion:" + alumnoId + ":" + cursoId;
        return redisTemplate.opsForHash().entries(key);
    }

    /** Indica si existe el HASH de sesión activa para el par alumno/curso. */
    public boolean existeSesion(String alumnoId, String cursoId) {
        String key = "sesion:" + alumnoId + ":" + cursoId;
        Boolean has = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(has);
    }

    /** Refresca el TTL de la sesión a 2 horas. Se llama desde OP-1 al consultar el panel. */
    public void refrescarTTLSesion(String alumnoId, String cursoId) {
        String key = "sesion:" + alumnoId + ":" + cursoId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.expire(key, 2, TimeUnit.HOURS);
        }
    }

    public void cerrarSesion(String alumnoId, String cursoId) { // Eliminamos la sesión del alumno para el curso cuando cierra sesión o se desconecta
        String key = "sesion:" + alumnoId + ":" + cursoId;
        redisTemplate.delete(key);
    }

    // ─── RANKING (SORTED SET) ────────────────────────────────

    public void actualizarPuntaje(String cursoId, String alumnoId, double puntaje) { // Actualizamos el puntaje del alumno en el ranking del curso utilizando un Sorted Set, donde el puntaje es la puntuación y el alumnoId es el miembro
        String key = "ranking:" + cursoId;
        redisTemplate.opsForZSet().add(key, alumnoId, puntaje);
    }

    public Set<String> getTop10(String cursoId) { // Obtenemos el top 10 de alumnos con mejor puntaje en el ranking del curso, ordenados de mayor a menor
        String key = "ranking:" + cursoId;
        return redisTemplate.opsForZSet().reverseRange(key, 0, 9);
    }

    public Long getPosicionAlumno(String cursoId, String alumnoId) { // Obtenemos la posición del alumno en el ranking del curso, ordenado de mayor a menor. Si el alumno no está en el ranking, devuelve null
        String key = "ranking:" + cursoId;
        Long pos = redisTemplate.opsForZSet().reverseRank(key, alumnoId);
        return pos != null ? pos + 1 : null;
    }

    public Set<String> getAlumnosPorRango(String cursoId, double minPuntaje, double maxPuntaje) { // Obtenemos los alumnos que tienen un puntaje dentro de un rango específico en el ranking del curso
        String key = "ranking:" + cursoId;
        return redisTemplate.opsForZSet().rangeByScore(key, minPuntaje, maxPuntaje);
    }

    // ─── COLA DE CORRECCIÓN (LIST) ───────────────────────────

    public void encolarEntrega(String cursoId, String trabajoJson) { // Encolamos la entrega del alumno en la cola de corrección del curso
        String key = "cola:correccion:" + cursoId;
        redisTemplate.opsForList().leftPush(key, trabajoJson);
    }

    public String desencolarSiguiente(String cursoId) { // Desencolamos la siguiente entrega del alumno para corregir en la cola de corrección del curso. Si la cola está vacía, devuelve null
        String key = "cola:correccion:" + cursoId;
        return redisTemplate.opsForList().rightPop(key);
    }

    public Long cantidadPendientes(String cursoId) { // Obtenemos la cantidad de entregas pendientes en la cola de corrección del curso
        String key = "cola:correccion:" + cursoId;
        return redisTemplate.opsForList().size(key);
    }

    // ─── ALUMNOS ACTIVOS (SET) ───────────────────────────────

    public void agregarAlumnoActivo(String cursoId, String alumnoId) { // Agregamos al alumno a la lista de alumnos activos en el curso
        String key = "activos:" + cursoId;
        redisTemplate.opsForSet().add(key, alumnoId);
    }

    public void removerAlumnoActivo(String cursoId, String alumnoId) { // Removemos al alumno de la lista de alumnos activos en el curso
        String key = "activos:" + cursoId;
        redisTemplate.opsForSet().remove(key, alumnoId);
    }

    public Set<String> getAlumnosActivos(String cursoId) { // Obtenemos la lista de alumnos activos en el curso
        String key = "activos:" + cursoId;
        return redisTemplate.opsForSet().members(key);
    }

    public Long cantidadActivos(String cursoId) { // Obtenemos la cantidad de alumnos activos en el curso
        String key = "activos:" + cursoId;
        return redisTemplate.opsForSet().size(key);
    }
}