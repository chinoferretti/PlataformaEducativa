# TODO — Pendientes del proyecto

## 1. `@ControllerAdvice` — manejo global de errores

**Qué falta:** no existe ninguna clase anotada con `@ControllerAdvice` en el proyecto.

**Por qué importa:** hoy cada endpoint tiene su propio bloque `try/catch` que devuelve
errores de forma inconsistente (algunos como `String`, otros sin body, etc.).
Un `@ControllerAdvice` centraliza eso: un solo lugar maneja `IllegalArgumentException`,
`IllegalStateException`, `RuntimeException`, etc. y devuelve siempre la misma estructura JSON.

**De dónde sale:** pedido en la división de trabajo, Persona 1 —
*"Crear las clases base: DTOs de respuesta, manejo de errores global (@ControllerAdvice)"*

**Dónde crearlo:** `src/main/java/com/grupo12/poliglota/config/GlobalExceptionHandler.java`
(o en un paquete `exception/`)

---

## 2. `MongoService.java` — capa de abstracción MongoDB

**Qué falta:** no existe ninguna clase `MongoService`. Hoy la lógica de MongoDB
está embebida directamente con `MongoTemplate` dentro de cada `OP_X_Service`.

**Por qué importa:** la división de trabajo asignó a la Persona 2 crear esta clase
con métodos reutilizables entre operaciones. Sin ella, la lógica Mongo está duplicada
o dispersa y es más difícil de mantener.

**Métodos que debería tener según la especificación:**
- `obtenerHistorialModulos(alumnoId, cursoId)` — usado en OP-1
- `persistirProgresoModulo(...)` + `actualizarPorcentaje(...)` — usado en OP-2
- `persistirCorreccion(...)` — usado en OP-4
- `obtenerInscriptosConProgreso(cursoId)` — usado en OP-3 (ver punto 3)
- `filtrarCursosPorIdiomaYModalidad(ids, idioma, modalidad)` — usado en OP-5

**De dónde sale:** pedido en la división de trabajo, Persona 2 —
*"MongoService.java con los métodos que usan las 5 operaciones"*

**Dónde crearlo:** `src/main/java/com/grupo12/poliglota/service/MongoService.java`

---

## 3. OP-3 — lista de inscriptos con progreso incompleta

**Qué falta:** `OP3_DashboardInstructorService` solo devuelve `totalInscriptos`
(un número leído del campo `total_inscriptos` del documento del curso).
No consulta la colección `inscripciones` para traer la lista real de alumnos
con su porcentaje de progreso individual.

**Por qué importa:** el dashboard de un instructor debería mostrar quién avanzó
cuánto, no solo un conteo. La especificación pide explícitamente
*"obtener lista de inscriptos con progreso para instructor"*.

**De dónde sale:** pedido en la división de trabajo, Persona 2 —
*"Obtener lista de inscriptos con progreso para instructor"* (método de MongoService
consumido por OP-3)

**Dónde arreglarlo:** agregar query a `inscripciones` en `OP3_DashboardInstructorService`
y agregar el campo `List<Map<String,Object>> inscriptosConProgreso` en
`DashboardInstructorResponse`.
