# Plataforma Educativa — TP2 Tema 3 (Ingeniería de Datos II)

Aplicación poliglota Spring Boot que integra **MongoDB + Neo4j + Redis** para el dominio de una plataforma educativa online.

> **Estado actual de la entrega:** OP-1, OP-2, OP-3 y OP-4 implementadas. OP-5 (Recomendación de próximo curso) pendiente.

---

## Requisitos

- **Java 21**
- **Maven** (o usar el wrapper `./mvnw`)
- **Docker** + Docker Compose
- Para los scripts de carga: **PowerShell** (los scripts viven en `scripts/*.ps1`)

---

## 1) Levantar los motores

```powershell
docker compose up -d
```

Esto deja corriendo:

| Servicio | Container          | Puerto host |
| -------- | ------------------ | ----------- |
| MongoDB  | `mongo_poliglota`  | `27017`     |
| Neo4j    | `neo4j_poliglota`  | `7474` (HTTP) / `7687` (Bolt) |
| Redis    | `redis_poliglota`  | `6379`      |

Credenciales Neo4j por defecto: `neo4j / password123`.

## 2) Cargar los datos del dominio

Los datasets viven en `../archivosUsados/` (alumnos.json, cursos.json, inscripciones.json, prerrequisitos.csv, rutas_certificacion.csv, redis_datos_prueba_coherentes.txt, etc.).

Ejecutar **en este orden** desde la raíz del proyecto:

```powershell
# Mongo (idempotente: usa --drop)
.\scripts\load_mongo.ps1

# Neo4j (idempotente: usa MERGE)
.\scripts\load_neo4j.ps1

# Redis (datos de prueba coherentes con Mongo)
.\scripts\load_redis.ps1
```

Cada script copia los archivos al contenedor correspondiente y ejecuta `mongoimport` / `cypher-shell` / `redis-cli` adentro.

> **Limitación conocida:** el SET `activos:{cursoId}` no se auto-limpia si la sesión expira por TTL — solo se limpia en OP-2 (cierre explícito). Si se quiere reciclar el entorno, correr `docker compose down -v` y volver a cargar.

## 3) Correr la aplicación

```powershell
./mvnw spring-boot:run
```

La app expone:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **API docs JSON:** http://localhost:8080/api-docs
- **Endpoints crudos de Redis:** `/api/redis/...`
- **Operaciones poliglotas:** `/api/operaciones/op{1..4}/...`

## 4) Variables de entorno

Todas las conexiones son configurables por env vars (ver `src/main/resources/application.properties`). Defaults entre paréntesis:

| Variable          | Default                                          |
| ----------------- | ------------------------------------------------ |
| `MONGO_URI`       | `mongodb://localhost:27017/plataforma_educativa` |
| `REDIS_HOST`      | `localhost`                                      |
| `REDIS_PORT`      | `6379`                                           |
| `REDIS_PASSWORD`  | *(vacío)*                                        |
| `NEO4J_URI`       | `bolt://localhost:7687`                          |
| `NEO4J_USER`      | `neo4j`                                          |
| `NEO4J_PASSWORD`  | `password123`                                    |
| `SERVER_PORT`     | `8080`                                           |

---

## Operaciones poliglotas

### OP-1 · Panel de alumno en cursado activo · 3 motores

`GET /api/operaciones/op1/panel-alumno`

Params:
- `alumnoId` — ObjectId hex del alumno
- `cursoId`  — ObjectId hex del curso
- `certObjetivo` *(opcional)* — id de la ruta de certificación (ej: `CERT-MED`)

Combina:
- **MongoDB** → inscripción, módulos completados, puntajes históricos
- **Neo4j**   → cursos que se habilitan si aprueba este + pasos a la certificación
- **Redis**   → estado de la sesión activa (HASH) + posición en el ranking

Cada llamada **refresca el TTL del HASH a 2h** (mirar el panel cuenta como actividad).

```bash
curl "http://localhost:8080/api/operaciones/op1/panel-alumno?alumnoId=69da83d24fd7e59767eb9640&cursoId=c4cfbb8a096802f6d67c8720&certObjetivo=CERT-MED"
```

### OP-2 · Cierre de sesión y persistencia de progreso · 3 motores

`POST /api/operaciones/op2/cerrar-sesion`

Body:
```json
{
  "alumnoId": "69da83d24fd7e59767eb9640",
  "cursoId":  "c4cfbb8a096802f6d67c8720",
  "estadoModulo": "completado",
  "puntajeObtenido": 75.0
}
```

Flujo:
1. **Redis** → HGETALL de `sesion:{alumno}:{curso}`
2. **MongoDB** → upsert idempotente en `progreso_modulos` por `(alumno_id, curso_id, orden_modulo)` + actualiza `inscripciones.porcentaje_progreso`
3. **Redis** → si fue evaluación con puntaje > 0, ZADD al ranking
4. **Neo4j** → si el curso quedó 100% completado, MERGE `(Alumno)-[:COMPLETO]->(Curso)`
5. **Redis** → DEL del HASH + SREM del SET de activos

**Coherencia ante fallo parcial:**
- Si Mongo falla → 500 y el HASH **NO** se borra (reintentable).
- Si Neo4j falla → 200 con `gradoConsistencia: "parcial"` y aviso en `avisos[]` (reconciliable).
- Si todo OK → `gradoConsistencia: "total"`.

### OP-3 · Dashboard del instructor · 2 motores (Mongo + Redis)

`GET /api/operaciones/op3/dashboard-instructor?cursoId={...}`

### OP-4 · Corrección de evaluación · 2 motores (Redis + Mongo)

`POST /api/operaciones/op4/corregir-evaluacion`

```json
{ "cursoId": "...", "puntaje": 8.5, "comentario": "OK", "instructorId": "..." }
```

### OP-5 · Recomendación de próximo curso · 3 motores

`GET /api/operaciones/op5/recomendar-curso`

Params:
- `alumnoId` — ObjectId hex del alumno
- `idioma` *(opcional)* — filtra cursos por idioma (ej: `español`)
- `modalidad` *(opcional)* — filtra cursos por modalidad (ej: `online`)

Combina:
- **Neo4j**   → determina qué cursos tiene desbloqueados el alumno: todos sus prerrequisitos completados y que él todavía no cursó
- **MongoDB** → trae los detalles de esos cursos (nombre, descripción, idioma, modalidad, nivel) y aplica los filtros opcionales
- **Redis**   → enriquece cada resultado con alumnos activos ahora (SET) y puntaje máximo del ranking (SORTED SET)

Si el alumno nunca completó ningún curso (no existe como nodo en Neo4j), se devuelven los cursos sin prerrequisitos, que están disponibles para todos.

```bash
curl "http://localhost:8080/api/operaciones/op5/recomendar-curso?alumnoId=69da83d24fd7e59767eb9640"
curl "http://localhost:8080/api/operaciones/op5/recomendar-curso?alumnoId=69da83d24fd7e59767eb9640&idioma=español&modalidad=online"
```

---

## Estructura del proyecto

```
src/main/java/com/grupo12/poliglota/
├── PoliglotaApplication.java
├── config/
│   ├── Neo4jConfig.java
│   └── RedisConfig.java
├── controller/
│   ├── OperacionesController.java   ← OP-1, OP-2, OP-3, OP-4
│   └── RedisController.java         ← CRUD directo Redis (§3.1, §3.2, §3.3)
├── dto/
│   ├── CierreSesionRequest.java     · CierreSesionResponse.java
│   ├── CorreccionRequest.java       · CorreccionResponse.java
│   ├── DashboardInstructorResponse.java
│   └── PanelAlumnoResponse.java
└── service/
    ├── RedisService.java            ← HASH/SORTED SET/LIST/SET
    ├── Neo4jService.java            ← grafo de prerrequisitos y rutas
    ├── OP1_PanelAlumnoService.java
    ├── OP2_CierreSesionService.java
    ├── OP3_DashboardInstructorService.java
    └── OP4_CorreccionEvaluacionService.java

scripts/
├── load_mongo.ps1
├── load_neo4j.ps1
├── load_neo4j.cypher
└── load_redis.ps1
```

## Modelo Neo4j

```
(:Curso {id, nombre})-[:REQUIERE {obligatorio}]->(:Curso)
(:RutaCertificacion {id, nombre, categoria, nivel, totalCursos})-[:INCLUYE {orden}]->(:Curso)
(:Alumno {id})-[:COMPLETO {fecha, puntaje}]->(:Curso)
```

Los nodos `Alumno` se crean **on-demand** desde OP-2 al completar un curso (MERGE). Esto evita tener que sincronizar el catálogo completo de alumnos contra Mongo.

## Convención de claves Redis

`entidad:identificador:atributo`

| Clave                     | Tipo       | Uso                                       |
| ------------------------- | ---------- | ----------------------------------------- |
| `sesion:{alumno}:{curso}` | HASH       | Estado de sesión activa (TTL 2h)          |
| `ranking:{curso}`         | SORTED SET | Ranking por puntaje                       |
| `cola:correccion:{curso}` | LIST       | FIFO de trabajos pendientes               |
| `activos:{curso}`         | SET        | Alumnos presentes en este momento         |
