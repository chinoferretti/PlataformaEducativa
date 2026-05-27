package com.grupo12.poliglota.controller;

import com.grupo12.poliglota.dto.CierreSesionRequest;
import com.grupo12.poliglota.dto.CierreSesionResponse;
import com.grupo12.poliglota.dto.CorreccionRequest;
import com.grupo12.poliglota.dto.CorreccionResponse;
import com.grupo12.poliglota.dto.DashboardInstructorResponse;
import com.grupo12.poliglota.dto.ErrorResponse;
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

    // ─── OP-1 ────────────────────────────────────────────────────────────────

    @GetMapping("/op1/panel-alumno")
    @Operation(
        summary = "OP-1: Panel de alumno en cursado activo",
        description = """
                Operación poliglota que combina los **3 motores**:
                - **MongoDB** → inscripción, módulos completados, puntajes históricos
                - **Neo4j**   → cursos que se habilitan si aprueba este, pasos a certificación objetivo
                - **Redis**   → estado de sesión activa (HASH) + posición en ranking (SORTED SET)

                Si no hay sesión activa en Redis se devuelven los demás campos con
                `sesionActiva = null`. Cada consulta al panel **refresca el TTL** del HASH a 2h.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Panel armado correctamente",
                     content = @Content(schema = @Schema(implementation = PanelAlumnoResponse.class))),
        @ApiResponse(responseCode = "400", description = "alumnoId o cursoId no son ObjectId válidos",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No existe inscripción para ese alumno/curso",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PanelAlumnoResponse> getPanelAlumno(
            @Parameter(description = "ObjectId hex del alumno", required = true)
            @RequestParam String alumnoId,
            @Parameter(description = "ObjectId hex del curso", required = true)
            @RequestParam String cursoId,
            @Parameter(description = "ID de la ruta de certificación objetivo. Opcional.")
            @RequestParam(required = false) String certObjetivo) {
        return ResponseEntity.ok(op1Service.obtenerPanel(alumnoId, cursoId, certObjetivo));
    }

    // ─── OP-2 ────────────────────────────────────────────────────────────────

    @PostMapping("/op2/cerrar-sesion")
    @Operation(
        summary = "OP-2: Cierre de sesión y persistencia de progreso",
        description = """
                Coordina los **3 motores** al cerrar una sesión de cursado:
                1. **Redis** → HGETALL del HASH de sesión
                2. **MongoDB** → upsert idempotente en `progreso_modulos` + actualiza porcentaje en `inscripciones`
                3. **Redis** → si fue evaluación con puntaje > 0, ZADD al ranking
                4. **Neo4j** → si el curso quedó 100%, crea (Alumno)-[:COMPLETO]->(Curso)
                5. **Redis** → DEL del HASH + SREM del SET de activos

                **Coherencia:** si Mongo falla → 500 y el HASH NO se borra (reintentable).
                Si Neo4j falla → 200 con `gradoConsistencia = "parcial"`.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sesión cerrada y progreso persistido",
                     content = @Content(schema = @Schema(implementation = CierreSesionResponse.class))),
        @ApiResponse(responseCode = "400", description = "IDs inválidos",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No hay sesión activa en Redis",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Fallo al persistir en MongoDB — reintentable",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CierreSesionResponse> cerrarSesion(@RequestBody CierreSesionRequest req) {
        return ResponseEntity.ok(op2Service.cerrarSesion(req));
    }

    // ─── OP-3 ────────────────────────────────────────────────────────────────

    @GetMapping("/op3/dashboard-instructor")
    @Operation(
        summary = "OP-3: Dashboard del Instructor",
        description = """
                Operación poliglota que combina **MongoDB** y **Redis**:
                - **MongoDB** → nombre, descripción, instructor, total de inscriptos,
                               lista de inscriptos con porcentaje de progreso individual
                - **Redis (SET)** → alumnos conectados en este momento
                - **Redis (Sorted Set)** → top 10 del ranking de puntajes
                - **Redis (List)** → cantidad de entregas esperando corrección
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dashboard generado correctamente",
                     content = @Content(schema = @Schema(implementation = DashboardInstructorResponse.class))),
        @ApiResponse(responseCode = "400", description = "cursoId inválido",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Curso no encontrado en MongoDB",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DashboardInstructorResponse> getDashboardInstructor(
            @Parameter(description = "ObjectId hex del curso", required = true)
            @RequestParam String cursoId) {
        return ResponseEntity.ok(op3Service.obtenerDashboard(cursoId));
    }

    // ─── OP-4 ────────────────────────────────────────────────────────────────

    @PostMapping("/op4/corregir-evaluacion")
    @Operation(
        summary = "OP-4: Corrección de Evaluación",
        description = """
                Operación poliglota que combina **Redis** y **MongoDB**:
                1. **Redis (List)** → desencola el siguiente trabajo (RPOP)
                2. **MongoDB** → persiste el resultado en la colección "evaluaciones"
                3. **Redis (Sorted Set)** → actualiza el puntaje del alumno en el ranking

                El JSON encolado debe tener al menos el campo `alumno_id`.
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Corrección realizada correctamente",
                     content = @Content(schema = @Schema(implementation = CorreccionResponse.class))),
        @ApiResponse(responseCode = "409", description = "La cola de corrección está vacía",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Error interno del servidor",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CorreccionResponse> corregirEvaluacion(@RequestBody CorreccionRequest request) {
        return ResponseEntity.ok(op4Service.corregirSiguiente(request));
    }

    // ─── OP-5 ────────────────────────────────────────────────────────────────

    @GetMapping("/op5/recomendar-cursos")
    @Operation(
        summary = "OP-5: Recomendación de próximo curso",
        description = """
                Operación poliglota que combina los **3 motores**:
                - **Neo4j**   → cursos desbloqueados: prerrequisitos completados y aún no cursados
                - **MongoDB** → detalles de cada curso con filtros opcionales por idioma y/o modalidad
                - **Redis**   → contexto en tiempo real: alumnos activos (SET) y puntaje máximo (SORTED SET)
                """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recomendaciones generadas correctamente",
                     content = @Content(schema = @Schema(implementation = RecomendacionResponse.class))),
        @ApiResponse(responseCode = "400", description = "alumnoId no es un ObjectId válido",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RecomendacionResponse> recomendarCurso(
            @Parameter(description = "ObjectId hex del alumno", required = true)
            @RequestParam String alumnoId,
            @Parameter(description = "Filtrar por idioma del curso. Opcional.")
            @RequestParam(required = false) String idioma,
            @Parameter(description = "Filtrar por modalidad del curso. Opcional.")
            @RequestParam(required = false) String modalidad) {
        return ResponseEntity.ok(op5Service.recomendar(alumnoId, idioma, modalidad));
    }
}
