// =====================================================================
// Carga del grafo en Neo4j: cursos, prerrequisitos y rutas de certificación.
//
// Pre-requisito: copiar los CSV al directorio /var/lib/neo4j/import del
// contenedor neo4j_poliglota (lo hace scripts/load_neo4j.ps1).
//
// Uso desde fuera:
//   cat scripts/load_neo4j.cypher | docker exec -i neo4j_poliglota \
//        cypher-shell -u neo4j -p password123
//
// Idempotente: usa MERGE.
// =====================================================================

// Limpieza opcional (descomentar para empezar de cero)
// MATCH (n) DETACH DELETE n;

// 1) Cursos: derivados de prerrequisitos.csv (campos curso_id, curso_nombre)
//    Cargamos primero los nombres de "curso" y "prerequisito" como Curso.
LOAD CSV WITH HEADERS FROM 'file:///prerrequisitos.csv' AS row
MERGE (c:Curso {id: row.curso_id})
  ON CREATE SET c.nombre = row.curso_nombre;

LOAD CSV WITH HEADERS FROM 'file:///prerrequisitos.csv' AS row
MERGE (p:Curso {id: row.prerequisito_id})
  ON CREATE SET p.nombre = row.prerequisito_nombre;

// 2) Relaciones de prerrequisito  (curso)-[:REQUIERE]->(prerequisito)
LOAD CSV WITH HEADERS FROM 'file:///prerrequisitos.csv' AS row
MATCH (c:Curso {id: row.curso_id})
MATCH (p:Curso {id: row.prerequisito_id})
MERGE (c)-[r:REQUIERE]->(p)
  SET r.obligatorio = toBoolean(row.obligatorio);

// 3) Rutas de certificación: cada fila tiene cursos="id1,id2,id3,..."
LOAD CSV WITH HEADERS FROM 'file:///rutas_certificacion.csv' AS row
MERGE (cert:RutaCertificacion {id: row._id})
  SET cert.nombre = row.nombre,
      cert.categoria = row.categoria,
      cert.nivel = row.nivel_requerido,
      cert.totalCursos = toInteger(row.total_cursos);

// 4) Relaciones (RutaCertificacion)-[:INCLUYE {orden}]->(Curso)
LOAD CSV WITH HEADERS FROM 'file:///rutas_certificacion.csv' AS row
MATCH (cert:RutaCertificacion {id: row._id})
WITH cert, split(row.cursos, ',') AS cursoIds
UNWIND range(0, size(cursoIds)-1) AS idx
WITH cert, trim(cursoIds[idx]) AS cursoId, idx
MERGE (c:Curso {id: cursoId})
MERGE (cert)-[r:INCLUYE]->(c)
  SET r.orden = idx + 1;

// 5) Índices para acelerar lookups por id
CREATE INDEX curso_id_idx IF NOT EXISTS FOR (c:Curso) ON (c.id);
CREATE INDEX alumno_id_idx IF NOT EXISTS FOR (a:Alumno) ON (a.id);
CREATE INDEX ruta_id_idx IF NOT EXISTS FOR (r:RutaCertificacion) ON (r.id);

// Verificación
MATCH (c:Curso) RETURN count(c) AS totalCursos;
MATCH ()-[r:REQUIERE]->() RETURN count(r) AS totalPrerrequisitos;
MATCH (cert:RutaCertificacion) RETURN count(cert) AS totalRutas;
