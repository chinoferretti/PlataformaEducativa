# TODO — Pendientes del proyecto

> Última actualización: 2026-05-27

---

## ~~1. `@ControllerAdvice` — manejo global de errores~~ ✅ COMPLETADO

**Archivos creados:**
- `src/main/java/com/grupo12/poliglota/config/GlobalExceptionHandler.java`
- `src/main/java/com/grupo12/poliglota/dto/ErrorResponse.java`
- `src/main/java/com/grupo12/poliglota/exception/ColaVaciaException.java`

**Qué se hizo:**
- `GlobalExceptionHandler` centraliza el manejo de errores con `@RestControllerAdvice`.
- Todos los errores devuelven la misma estructura JSON: `{ status, error, message, timestamp }`.
- Mapeo de excepciones: `IllegalArgumentException` → 400, `IllegalStateException` → 404,
  `ColaVaciaException` → 409, `RuntimeException` → 500.
- `OperacionesController` eliminó todos los bloques `try/catch`; cada endpoint quedó en una sola línea.
- OP-1 ahora lanza `IllegalStateException` para "no existe inscripción" (→ 404).
- OP-3 ahora lanza `IllegalStateException` para "curso no encontrado" (→ 404).
- OP-4 ahora lanza `ColaVaciaException` para "cola vacía" (→ 409).

---

## ~~2. `MongoService.java` — capa de abstracción MongoDB~~ ✅ COMPLETADO

**Archivo creado:** `src/main/java/com/grupo12/poliglota/service/MongoService.java`

**Métodos implementados:**

| Método | Usado en |
|---|---|
| `obtenerInscripcion(alumnoOid, cursoOid)` | OP-1 |
| `actualizarPorcentajeInscripcion(...)` | OP-2 |
| `obtenerInscriptosConProgreso(cursoOid)` | OP-3 |
| `obtenerCurso(cursoOid)` | OP-1, OP-2, OP-3 |
| `filtrarCursosPorIdiomaYModalidad(ids, idioma, modalidad)` | OP-5 |
| `obtenerHistorialModulos(alumnoOid, cursoOid)` | OP-1 |
| `persistirProgresoModulo(...)` | OP-2 |
| `contarModulosCompletados(alumnoOid, cursoOid)` | OP-2 |
| `persistirCorreccion(...)` | OP-4 |

**Qué se hizo:**
- Los 5 servicios OP ya no inyectan `MongoTemplate` directamente; usan `MongoService`.

---

## ~~3. OP-3 — lista de inscriptos con progreso incompleta~~ ✅ COMPLETADO

**Archivos modificados:**
- `src/main/java/com/grupo12/poliglota/service/OP3_DashboardInstructorService.java`
- `src/main/java/com/grupo12/poliglota/dto/DashboardInstructorResponse.java`

**Qué se hizo:**
- `OP3_DashboardInstructorService` ahora llama a `mongoService.obtenerInscriptosConProgreso(cursoOid)`
  que consulta la colección `inscripciones` filtrando por `curso_id`.
- `DashboardInstructorResponse` tiene el nuevo campo
  `List<Map<String, Object>> inscriptosConProgreso`.
- Cada elemento de la lista contiene: `alumnoId`, `estado`, `porcentajeProgreso`.

---

## Pendientes conocidos (no críticos)

- **`Neo4jService` — falta método `crearInscripcion`**: `actualizarProgreso` en OP-2 usa `MATCH`
  sobre la relación `INSCRIPTO_EN`. Si esa relación no existe en el grafo (datos no seeded),
  el SET se ejecuta en vacío sin error. Agregar un método `crearInscripcion(alumnoId, cursoId)`
  con `MERGE` que se llame al momento del alta del alumno al curso.

- **Inconsistencia de tipo en `evaluaciones`**: OP-4 guarda `alumno_id` como `String`
  en la colección `evaluaciones`, mientras que `inscripciones` y `progreso_modulos`
  lo guardan como `ObjectId`. Unificar si se necesitan joins entre colecciones.

