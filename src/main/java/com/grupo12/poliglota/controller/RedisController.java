package com.grupo12.poliglota.controller;

import com.grupo12.poliglota.service.RedisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
@Tag(name = "Redis", description = "Operaciones de persistencia en tiempo real con Redis")
public class RedisController {

    private final RedisService redisService;

    // ─────────────────────────────────────────────
    // SESIÓN (HASH)
    // ─────────────────────────────────────────────

    @PostMapping("/sesion/iniciar")
    @Operation(summary = "Iniciar sesión de alumno",
               description = "Crea un HASH en Redis con los datos de la sesión activa. TTL: 2 horas.")
    public ResponseEntity<String> iniciarSesion(
            @Parameter(description = "ID del alumno, ej: A00123") @RequestParam String alumnoId,
            @Parameter(description = "ID del curso, ej: C045")    @RequestParam String cursoId,
            @Parameter(description = "ID del módulo actual")       @RequestParam String moduloId) {
        redisService.iniciarSesion(alumnoId, cursoId, moduloId);
        return ResponseEntity.ok("Sesión iniciada: sesion:" + alumnoId + ":" + cursoId);
    }

    @PutMapping("/sesion/posicion")
    @Operation(summary = "Actualizar posición en el video",
               description = "Actualiza el campo posicion_segundos y ultima_actividad en el HASH de sesión.")
    public ResponseEntity<String> actualizarPosicion(
            @RequestParam String alumnoId,
            @RequestParam String cursoId,
            @RequestParam int segundos) {
        redisService.actualizarPosicion(alumnoId, cursoId, segundos);
        return ResponseEntity.ok("Posición actualizada a " + segundos + "s");
    }

    @PutMapping("/sesion/respuestas")
    @Operation(summary = "Guardar respuestas parciales",
               description = "Persiste un JSON con las respuestas parciales dentro del HASH de sesión.")
    public ResponseEntity<String> guardarRespuestaParcial(
            @RequestParam String alumnoId,
            @RequestParam String cursoId,
            @RequestParam String respuestasJson) {
        redisService.guardarRespuestaParcial(alumnoId, cursoId, respuestasJson);
        return ResponseEntity.ok("Respuestas parciales guardadas");
    }

    @GetMapping("/sesion")
    @Operation(summary = "Obtener datos de sesión activa",
               description = "Devuelve todos los campos del HASH de sesión del alumno en el curso.")
    public ResponseEntity<Map<Object, Object>> obtenerSesion(
            @RequestParam String alumnoId,
            @RequestParam String cursoId) {
        Map<Object, Object> sesion = redisService.obtenerSesion(alumnoId, cursoId);
        if (sesion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(sesion);
    }

    @DeleteMapping("/sesion/cerrar")
    @Operation(summary = "Cerrar sesión de alumno",
               description = "Elimina el HASH de sesión de Redis (se usa al finalizar la actividad).")
    public ResponseEntity<String> cerrarSesion(
            @RequestParam String alumnoId,
            @RequestParam String cursoId) {
        redisService.cerrarSesion(alumnoId, cursoId);
        return ResponseEntity.ok("Sesión cerrada y eliminada de Redis");
    }

    // ─────────────────────────────────────────────
    // RANKING (SORTED SET)
    // ─────────────────────────────────────────────

    @PostMapping("/ranking/puntaje")
    @Operation(summary = "Actualizar puntaje de alumno en ranking",
               description = "Inserta o actualiza el puntaje del alumno en el SORTED SET del curso.")
    public ResponseEntity<String> actualizarPuntaje(
            @RequestParam String cursoId,
            @RequestParam String alumnoId,
            @RequestParam double puntaje) {
        redisService.actualizarPuntaje(cursoId, alumnoId, puntaje);
        return ResponseEntity.ok("Puntaje " + puntaje + " asignado a " + alumnoId + " en curso " + cursoId);
    }

    @GetMapping("/ranking/top10")
    @Operation(summary = "Obtener top 10 del ranking",
               description = "Retorna los 10 alumnos con mayor puntaje en el curso (orden descendente).")
    public ResponseEntity<Set<String>> getTop10(
            @RequestParam String cursoId) {
        return ResponseEntity.ok(redisService.getTop10(cursoId));
    }

    @GetMapping("/ranking/posicion")
    @Operation(summary = "Obtener posición de un alumno en el ranking",
               description = "Devuelve la posición (1-based) del alumno dentro del ranking del curso.")
    public ResponseEntity<Long> getPosicionAlumno(
            @RequestParam String cursoId,
            @RequestParam String alumnoId) {
        Long pos = redisService.getPosicionAlumno(cursoId, alumnoId);
        if (pos == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(pos);
    }

    @GetMapping("/ranking/rango")
    @Operation(summary = "Obtener alumnos por rango de puntaje",
               description = "Filtra alumnos cuyo puntaje esté entre minPuntaje y maxPuntaje (inclusive).")
    public ResponseEntity<Set<String>> getAlumnosPorRango(
            @RequestParam String cursoId,
            @RequestParam double minPuntaje,
            @RequestParam double maxPuntaje) {
        return ResponseEntity.ok(redisService.getAlumnosPorRango(cursoId, minPuntaje, maxPuntaje));
    }

    // ─────────────────────────────────────────────
    // COLA DE CORRECCIÓN (LIST)
    // ─────────────────────────────────────────────

    @PostMapping("/cola/encolar")
    @Operation(summary = "Encolar entrega para corrección",
               description = "Agrega un trabajo al frente de la LIST (cola FIFO) de corrección del curso.")
    public ResponseEntity<String> encolarEntrega(
            @RequestParam String cursoId,
            @RequestParam String trabajoJson) {
        redisService.encolarEntrega(cursoId, trabajoJson);
        return ResponseEntity.ok("Entrega encolada en cola:correccion:" + cursoId);
    }

    @DeleteMapping("/cola/desencolar")
    @Operation(summary = "Desencolar siguiente entrega",
               description = "Extrae y devuelve el próximo trabajo a corregir (pop desde el final de la LIST).")
    public ResponseEntity<String> desencolarSiguiente(
            @RequestParam String cursoId) {
        String trabajo = redisService.desencolarSiguiente(cursoId);
        if (trabajo == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(trabajo);
    }

    @GetMapping("/cola/pendientes")
    @Operation(summary = "Cantidad de entregas pendientes",
               description = "Devuelve la cantidad de trabajos en espera de corrección en la cola del curso.")
    public ResponseEntity<Long> cantidadPendientes(
            @RequestParam String cursoId) {
        return ResponseEntity.ok(redisService.cantidadPendientes(cursoId));
    }

    // ─────────────────────────────────────────────
    // ALUMNOS ACTIVOS (SET)
    // ─────────────────────────────────────────────

    @PostMapping("/activos/agregar")
    @Operation(summary = "Registrar alumno como activo",
               description = "Agrega al alumno al SET de alumnos activos del curso en este momento.")
    public ResponseEntity<String> agregarAlumnoActivo(
            @RequestParam String cursoId,
            @RequestParam String alumnoId) {
        redisService.agregarAlumnoActivo(cursoId, alumnoId);
        return ResponseEntity.ok(alumnoId + " registrado como activo en curso " + cursoId);
    }

    @DeleteMapping("/activos/remover")
    @Operation(summary = "Remover alumno de activos",
               description = "Elimina al alumno del SET de activos (se llama al cerrar sesión o por timeout).")
    public ResponseEntity<String> removerAlumnoActivo(
            @RequestParam String cursoId,
            @RequestParam String alumnoId) {
        redisService.removerAlumnoActivo(cursoId, alumnoId);
        return ResponseEntity.ok(alumnoId + " removido de activos en curso " + cursoId);
    }

    @GetMapping("/activos")
    @Operation(summary = "Listar alumnos activos en un curso",
               description = "Devuelve el SET completo de alumnos conectados en este momento al curso.")
    public ResponseEntity<Set<String>> getAlumnosActivos(
            @RequestParam String cursoId) {
        return ResponseEntity.ok(redisService.getAlumnosActivos(cursoId));
    }

    @GetMapping("/activos/cantidad")
    @Operation(summary = "Cantidad de alumnos activos",
               description = "Retorna el número total de alumnos activos en el curso (tamaño del SET).")
    public ResponseEntity<Long> cantidadActivos(
            @RequestParam String cursoId) {
        return ResponseEntity.ok(redisService.cantidadActivos(cursoId));
    }
}
