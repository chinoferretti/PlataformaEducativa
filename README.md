# Plataforma Educativa — TP2 Tema 3 (Ingeniería de Datos II)

Aplicación poliglota Spring Boot que integra **MongoDB + Neo4j + Redis** para el dominio de una plataforma educativa online.

---

## Requisitos previos

| Herramienta | Versión mínima |
|---|---|
| Java | 21 |
| Maven | incluido (usar `.\mvnw`) |
| Docker Desktop | cualquier versión reciente |
| PowerShell | 5.1+ (incluido en Windows 10/11) |

> **Importante:** si tenés MongoDB instalado localmente, el servicio local compite con el contenedor Docker en el puerto `27017`. Antes de continuar, detené el servicio local desde `services.msc` o con `net stop MongoDB`. De lo contrario la aplicación y Compass se conectan al MongoDB local y no ven los datos importados.

---

## Paso 1 — Levantar los contenedores

Desde la raíz del proyecto:

docker compose up -d

Esto deja corriendo tres contenedores:

| Motor | Contenedor | Puerto |
|---|---|---|
| MongoDB | `mongo_poliglota` | `27017` |
| Neo4j | `neo4j_poliglota` | `7474` (UI) / `7687` (Bolt) |
| Redis | `redis_poliglota` | `6379` |

Neo4j UI: `http://localhost:7474` — credenciales: `neo4j / password123`

---

## Paso 2 — Cargar los datos

Los archivos de datos (JSON, CSV, TXT) están en la **raíz del proyecto**. Ejecutá los tres scripts en este orden desde la raíz:

# 1. MongoDB — 6 colecciones (usa --drop, es idempotente)
.\scripts\load_mongo.ps1

# 2. Neo4j — nodos Curso y RutaCertificacion con relaciones REQUIERE e INCLUYE
.\scripts\load_neo4j.ps1

# 3. Redis — sesiones, rankings, colas y alumnos activos de prueba
.\scripts\load_redis.ps1

Al terminar deberías tener:

| Colección MongoDB | Documentos |
|---|---|
| `cursos` | 400 |
| `inscripciones` | 1099 |
| `alumnos` | 150 |
| `progreso_modulos` | 352 |
| `instructores` | 10 |
| `certificados` | 16 |

Podés verificarlo en **MongoDB Compass** conectándote a `mongodb://localhost:27017` y entrando a la base `plataforma_educativa`.

---

## Paso 3 — Ejecutar la aplicación

.\mvnw spring-boot:run

Esperá hasta ver el banner de Spring Boot con el puerto `8080`. La aplicación expone:

| Recurso | URL |
|---|---|
| **Swagger UI** | http://localhost:8080/swagger-ui.html |
| API docs (JSON) | http://localhost:8080/api-docs |
| Operaciones poliglotas | `/api/operaciones/op{1..5}/...` |
| Endpoints Redis (CRUD) | `/api/redis/...` |

---

## Paso 4 — Probar las operaciones en Swagger

Abrí `http://localhost:8080/swagger-ui.html`. A continuación se listan los IDs reales del dataset para probar cada operación.

### IDs de prueba

| Entidad | ID |
|---|---|
| Alumno (Daniela Martínez) | `69da83d24fd7e59767eb969b` |
| Curso (Introducción a Pintura Elemental) | `a998e2dff83012bf1e06c1e7` |
| Instructor del curso | `69da7b979910d1443467655f` |

---

### OP-1 · Panel de alumno en cursado activo

`GET /api/operaciones/op1/panel-alumno`

Combina **MongoDB + Neo4j + Redis**.

| Parámetro | Valor |
|---|---|
| `alumnoId` | `69da83d24fd7e59767eb969b` |
| `cursoId` | `a998e2dff83012bf1e06c1e7` |
| `certObjetivo` | *(dejar vacío)* |

Devuelve: inscripción, módulos completados, puntajes históricos (MongoDB), cursos desbloqueados y pasos a certificación (Neo4j), sesión activa y posición en ranking (Redis).

---

### OP-2 · Cierre de sesión y persistencia de progreso

`POST /api/operaciones/op2/cerrar-sesion`

Combina **Redis + MongoDB + Neo4j**. Requiere una sesión activa en Redis. Si no hay, creá una primero.

**Paso previo** — `POST /api/redis/sesion/iniciar`

| Parámetro | Valor |
|---|---|
| `alumnoId` | `69da83d24fd7e59767eb969b` |
| `cursoId` | `a998e2dff83012bf1e06c1e7` |
| `moduloId` | `modulo_3` |

**Luego** — `POST /api/operaciones/op2/cerrar-sesion`

```json
{
  "alumnoId": "69da83d24fd7e59767eb969b",
  "cursoId": "a998e2dff83012bf1e06c1e7",
  "estadoModulo": "completado",
  "puntajeObtenido": 90.0
}
```

Devuelve: progreso guardado en MongoDB, ranking actualizado en Redis, grafo actualizado en Neo4j, sesión eliminada de Redis.

Coherencia ante fallos parciales:
- Si MongoDB falla → `500`, el HASH de Redis **no** se borra (reintentable)
- Si Neo4j falla → `200` con `gradoConsistencia: "parcial"` (reconciliable)

---

### OP-3 · Dashboard del instructor

`GET /api/operaciones/op3/dashboard-instructor`

Combina **MongoDB + Redis**.

| Parámetro | Valor |
|---|---|
| `cursoId` | `a998e2dff83012bf1e06c1e7` |

Devuelve: datos del curso, lista de inscriptos con progreso (MongoDB), alumnos activos ahora, top 10 ranking y entregas pendientes (Redis).

---

### OP-4 · Corrección de evaluación

`POST /api/operaciones/op4/corregir-evaluacion`

Combina **Redis + MongoDB**. Requiere al menos un trabajo en la cola de corrección del curso.

**Paso previo** — `POST /api/redis/cola/encolar`

| Parámetro | Valor |
|---|---|
| `cursoId` | `a998e2dff83012bf1e06c1e7` |
| `trabajoJson` | `{"alumno_id":"69da83d24fd7e59767eb969b","respuestas":{"p1":"A","p2":"C"}}` |

**Luego** — `POST /api/operaciones/op4/corregir-evaluacion`

```json
{
  "cursoId": "a998e2dff83012bf1e06c1e7",
  "instructorId": "69da7b979910d1443467655f",
  "puntaje": 85.0,
  "comentario": "Buen trabajo, revisar pregunta 2"
}
```

Devuelve: trabajo desencolado de Redis, corrección persistida en MongoDB (colección `evaluaciones`), ranking actualizado en Redis.

---

### OP-5 · Recomendación de próximo curso

`GET /api/operaciones/op5/recomendar-cursos`

Combina **Neo4j + MongoDB + Redis**.

| Parámetro | Valor |
|---|---|
| `alumnoId` | `69da83d24fd7e59767eb969b` |
| `idioma` | *(opcional, ej: `es`)* |
| `modalidad` | *(opcional, ej: `virtual`)* |

Devuelve: cursos desbloqueados según prerrequisitos completados (Neo4j), detalles y filtros aplicados (MongoDB), alumnos activos y puntaje máximo por curso (Redis).

---

## Variables de entorno

Todas las conexiones tienen defaults que apuntan a `localhost`. Se pueden sobreescribir con variables de entorno:

| Variable | Default |
|---|---|
| `MONGO_URI` | `mongodb://localhost:27017/plataforma_educativa` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | *(vacío)* |
| `NEO4J_URI` | `bolt://localhost:7687` |
| `NEO4J_USER` | `neo4j` |
| `NEO4J_PASSWORD` | `password123` |

---

## Estructura del proyecto

```
src/main/java/com/grupo12/poliglota/
├── PoliglotaApplication.java
├── config/
│   ├── Neo4jConfig.java
│   ├── RedisConfig.java
│   └── GlobalExceptionHandler.java
├── controller/
│   ├── OperacionesController.java   ← OP-1 a OP-5
│   └── RedisController.java         ← CRUD directo Redis
├── dto/
│   ├── PanelAlumnoResponse.java
│   ├── CierreSesionRequest/Response.java
│   ├── DashboardInstructorResponse.java
│   ├── CorreccionRequest/Response.java
│   ├── RecomendacionResponse.java
│   └── ErrorResponse.java
└── service/
    ├── MongoService.java
    ├── RedisService.java
    ├── Neo4jService.java
    ├── OP1_PanelAlumnoService.java
    ├── OP2_CierreSesionService.java
    ├── OP3_DashboardInstructorService.java
    ├── OP4_CorreccionEvaluacionService.java
    └── OP5_RecomendacionCursoService.java

scripts/
├── load_mongo.ps1
├── load_neo4j.ps1
├── load_neo4j.cypher
└── load_redis.ps1
```

---

## Modelo de datos Neo4j

```
(:Curso {id, nombre})-[:REQUIERE {obligatorio}]->(:Curso)
(:RutaCertificacion {id, nombre, categoria, nivel, totalCursos})-[:INCLUYE {orden}]->(:Curso)
(:Alumno {id})-[:COMPLETO {fecha, puntaje}]->(:Curso)
(:Alumno {id})-[:INSCRIPTO_EN {porcentaje}]->(:Curso)
```

Los nodos `Alumno` se crean on-demand desde OP-2 al completar un curso (`MERGE`).

---

## Claves Redis

| Clave | Tipo | Descripción |
|---|---|---|
| `sesion:{alumnoId}:{cursoId}` | HASH | Estado de sesión activa (TTL 2 h) |
| `ranking:{cursoId}` | SORTED SET | Ranking de alumnos por puntaje |
| `cola:correccion:{cursoId}` | LIST | Cola FIFO de entregas pendientes |
| `activos:{cursoId}` | SET | Alumnos conectados en este momento |
