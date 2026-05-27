package com.grupo12.poliglota.controller;

import com.grupo12.poliglota.dto.CierreSesionRequest;
import com.grupo12.poliglota.dto.CierreSesionResponse;
import com.grupo12.poliglota.dto.CorreccionRequest;
import com.grupo12.poliglota.dto.CorreccionResponse;
import com.grupo12.poliglota.dto.DashboardInstructorResponse;
import com.grupo12.poliglota.dto.PanelAlumnoResponse;
import com.grupo12.poliglota.dto.RecomendacionResponse;
import com.grupo12.poliglota.service.OP1_PanelAlumnoService;
import com.grupo12.poliglota.service.OP2_CierreSesionService;
import com.grupo12.poliglota.service.OP3_DashboardInstructorService;
import com.grupo12.poliglota.service.OP4_CorreccionEvaluacionService;
import com.grupo12.poliglota.service.OP5_RecomendacionCursoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/*
Persona 1  → OP-3, OP-4  (MongoDB + Redis)
Persona 2  → OP-1, OP-2  (MongoDB + Neo4j + Redis)
Persona 3  → OP-5        (Neo4j + MongoDB + Redis)
*/

@RestController
@RequestMapping("/api/operaciones")
@RequiredArgsConstructor
@Tag(name = "Operaciones Poliglotas",
     description = "Operaciones de negocio que combinan múltiples motores de base de datos")
public class OperacionesController {

    private final OP1_PanelAlumnoService op1Service;
    private final OP2_CierreSesionService op2Service;
    private final OP3_DashboardInstructorService op3Service;
    private final OP4_CorreccionEvaluacionService op4Service;
    private final OP5_RecomendacionCursoService op5Service;

    @GetMapping("/op1/panel-alumno") // OP-1: Panel de alumno en cursado activo (MongoDB + Neo4j + Redis)
    @Operation(
        summary = "OP-1: Panel de alumno en cursado activo",
        description = """
                Operación poliglota que combina los **3 motores**:
                - **MongoDB** → inscripción, módulos completados, puntajes históricos
                - **Neo4j**   → cursos que se habilitan si aprueba este, pasos a certificación objetivo
                - **Redis**   → estado de sesión activa (HASH) + posición en ranking (SORTED SET)

                Si no hay sesión activa en Redis se devuelven los demás campos con
                `sesionActiva = null`. Cada consulta al panel **refresca el TTL** del HASH
                a 2h (mirar el panel cuenta como actividad).
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Panel armado correctamente",
                     content = @Content(schema = @Schema(implementation = PanelAlumnoResponse.class))),
        @ApiResponse(responseCode = "400", description = "alumnoId o cursoId no son ObjectId válidos"),
        @ApiResponse(responseCode = "404", description = "No existe inscripción para ese alumno/curso")
    })
    public ResponseEntity<?> getPanelAlumno(
            @Parameter(description = "ObjectId hex del alumno", required = true)
            @RequestParam String alumnoId,
            @Parameter(description = "ObjectId hex del curso", required = true)
            @RequestParam String cursoId,
            @Parameter(description = "ID de la ruta de certificación objetivo (ej: CERT-MED). Opcional.")
            @RequestParam(required = false) String certObjetivo) {
        try {
            PanelAlumnoResponse panel = op1Service.obtenerPanel(alumnoId, cursoId, certObjetivo);
            return ResponseEntity.ok(panel);
        } catch (IllegalArgumentException e) {
            // No existe inscripción o IDs inválidos
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("No existe inscripción")) {
                return ResponseEntity.status(404).body(msg);
            }
            return ResponseEntity.badRequest().body(msg);
        }
    }

    @PostMapping("/op2/cerrar-sesion") // OP-2: Cierre de sesión y persistencia de progreso (3 motores)
    @Operation(
        summary = "OP-2: Cierre de sesión y persistencia de progreso",
        description = """
                Coordina los **3 motores** al cerrar una sesión de cursado:
                1. **Redis** → HGETALL del HASH de sesión
                2. **MongoDB** → upsert idempotente en `progreso_modulos` por
                   (alumno_id, curso_id, orden_modulo) + actualiza porcentaje en `inscripciones`
                3. **Redis** → si fue evaluación con puntaje > 0, ZADD al ranking
                4. **Neo4j** → si el curso quedó 100% completado, crea (Alumno)-[:COMPLETO]->(Curso)
                5. **Redis** → DEL del HASH + SREM del SET de activos

                **Coherencia:** si Mongo falla → 500 y el HASH NO se borra (reintentable).
                Si Neo4j falla → 200 con `gradoConsistencia = "parcial"` y un aviso en la respuesta.
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sesión cerrada y progreso persistido",
                     content = @Content(schema = @Schema(implementation = CierreSesionResponse.class))),
        @ApiResponse(responseCode = "400", description = "IDs inválidos"),
        @ApiResponse(responseCode = "404", description = "No hay sesión activa en Redis"),
        @ApiResponse(responseCode = "500", description = "Fallo al persistir en MongoDB - reintentable")
    })
    public ResponseEntity<?> cerrarSesion(@RequestBody CierreSesionRequest req) {
        try {
            return ResponseEntity.ok(op2Service.cerrarSesion(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    @GetMapping("/op3/dashboard-instructor") // OP-3: Dashboard del Instructor  (MongoDB + Redis)
    @Operation(
        summary = "OP-3: Dashboard del Instructor",
        description = """
                Operación poliglota que combina **MongoDB** y **Redis**:
                - **MongoDB** → nombre del curso, descripción, instructor, total de inscriptos
                - **Redis (SET)** → alumnos conectados en este momento
                - **Redis (Sorted Set)** → top 10 del ranking de puntajes
                - **Redis (List)** → cantidad de entregas esperando corrección

                Ideal para un panel que el instructor refresca frecuentemente.
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dashboard generado correctamente",
                     content = @Content(schema = @Schema(implementation = DashboardInstructorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Curso no encontrado en MongoDB"),
        @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<?> getDashboardInstructor(
            @Parameter(description = "ID del curso, ej: C045", required = true)
            @RequestParam String cursoId) {
        try {
            DashboardInstructorResponse response = op3Service.obtenerDashboard(cursoId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/op5/recomendar-curso") // OP-5: Recomendación de próximo curso (Neo4j + MongoDB + Redis)
    @Operation(
        summary = "OP-5: Recomendación de próximo curso",
        description = """
                Operación poliglota que combina los **3 motores**:
                - **Neo4j**   → cursos desbloqueados: prerrequisitos completados por el alumno
                               y que todavía no cursó
                - **MongoDB** → detalles de cada curso (nombre, descripción, idioma, modalidad, nivel)
                               con filtros opcionales por idioma y/o modalidad
                - **Redis**   → contexto en tiempo real: alumnos activos ahora en ese curso
                               y puntaje máximo del ranking

                Si el alumno nunca completó un curso (no existe en Neo4j), se devuelven
                los cursos sin prerrequisitos, que están disponibles para todos.
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recomendaciones generadas correctamente",
                     content = @Content(schema = @Schema(implementation = RecomendacionResponse.class))),
        @ApiResponse(responseCode = "400", description = "alumnoId no es un ObjectId válido")
    })
    public ResponseEntity<?> recomendarCurso(
            @Parameter(description = "ObjectId hex del alumno", required = true)
            @RequestParam String alumnoId,
            @Parameter(description = "Filtrar por idioma del curso (ej: español). Opcional.")
            @RequestParam(required = false) String idioma,
            @Parameter(description = "Filtrar por modalidad del curso (ej: online). Opcional.")
            @RequestParam(required = false) String modalidad) {
        try {
            RecomendacionResponse response = op5Service.recomendar(alumnoId, idioma, modalidad);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/op4/corregir-evaluacion") // OP-4: Corrección de Evaluación  (Redis + MongoDB)
    @Operation(
        summary = "OP-4: Corrección de Evaluación",
        description = """
                Operación poliglota que combina **Redis** y **MongoDB**:
                1. **Redis (List)** → desencola el siguiente trabajo de la cola de corrección
                2. **MongoDB** → persiste el resultado de la corrección en la colección "evaluaciones"
                3. **Redis (Sorted Set)** → actualiza el puntaje del alumno en el ranking

                El JSON que se encola en la cola debe tener al menos el campo `alumno_id`.
                Ejemplo de trabajo encolado:
                ```json
                { "alumno_id": "A00123", "entrega_id": "ENT-001", "archivo": "tp1.pdf" }
                ```
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Corrección realizada correctamente",
                     content = @Content(schema = @Schema(implementation = CorreccionResponse.class))),
        @ApiResponse(responseCode = "409", description = "La cola de corrección está vacía"),
        @ApiResponse(responseCode = "500", description = "Error interno del servidor")
    })
    public ResponseEntity<?> corregirEvaluacion(
            @RequestBody CorreccionRequest request) {
        try {
            CorreccionResponse response = op4Service.corregirSiguiente(request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            // Cola vacía → 409 Conflict
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }
}
