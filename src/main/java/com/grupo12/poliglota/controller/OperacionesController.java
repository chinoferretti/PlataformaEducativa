package com.grupo12.poliglota.controller;

import com.grupo12.poliglota.dto.CorreccionRequest;
import com.grupo12.poliglota.dto.CorreccionResponse;
import com.grupo12.poliglota.dto.DashboardInstructorResponse;
import com.grupo12.poliglota.service.OP3_DashboardInstructorService;
import com.grupo12.poliglota.service.OP4_CorreccionEvaluacionService;
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

    private final OP3_DashboardInstructorService op3Service;
    private final OP4_CorreccionEvaluacionService op4Service;

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
