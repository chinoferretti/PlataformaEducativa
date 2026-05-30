package com.grupo12.poliglota.service;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MongoService {

    private final MongoTemplate mongoTemplate;

    // Inscripciones
    public Document obtenerInscripcion(ObjectId alumnoOid, ObjectId cursoOid) {
        return mongoTemplate.findOne(
            new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid)
            )),
            Document.class, "inscripciones"
        );
    }

    public void actualizarPorcentajeInscripcion(ObjectId alumnoOid, ObjectId cursoOid,
                                                 double porcentaje, boolean completado) {
        mongoTemplate.upsert(
            new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid)
            )),
            new Update()
                .set("porcentaje_progreso", porcentaje)
                .set("estado", completado ? "completada" : "activa"),
            "inscripciones"
        );
    }

    // Lista de alumnos inscritos con porcentaje de progreso individual (OP-3)
    public List<Map<String, Object>> obtenerInscriptosConProgreso(ObjectId cursoOid) {
        List<Document> docs = mongoTemplate.find(
            new Query(Criteria.where("curso_id").is(cursoOid)),
            Document.class, "inscripciones"
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> item = new HashMap<>();
            Object aId = d.get("alumno_id");
            item.put("alumnoId", aId != null ? aId.toString() : null);
            item.put("estado",   d.getString("estado"));
            Object pct = d.get("porcentaje_progreso");
            item.put("porcentajeProgreso", pct instanceof Number n ? n.doubleValue() : 0.0);
            result.add(item);
        }
        return result;
    }

    // Cursos
    public Document obtenerCurso(ObjectId cursoOid) {
        return mongoTemplate.findOne(
            new Query(Criteria.where("_id").is(cursoOid)),
            Document.class, "cursos"
        );
    }

    // Devuelve los cursos cuyos _id están en la lista, con filtros opcionales de idioma y modalidad
    public List<Document> filtrarCursosPorIdiomaYModalidad(List<ObjectId> ids,
                                                            String idioma,
                                                            String modalidad) {
        Criteria criteria = Criteria.where("_id").in(ids);
        if (idioma != null && !idioma.isBlank())
            criteria = criteria.and("idioma").is(idioma);
        if (modalidad != null && !modalidad.isBlank())
            criteria = criteria.and("modalidad").is(modalidad);
        return mongoTemplate.find(new Query(criteria), Document.class, "cursos");
    }

    // Progreso de módulos
    public List<Document> obtenerHistorialModulos(ObjectId alumnoOid, ObjectId cursoOid) {
        return mongoTemplate.find(
            new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid)
            )),
            Document.class, "progreso_modulos"
        );
    }

    public void persistirProgresoModulo(ObjectId alumnoOid, ObjectId cursoOid,
                                        int ordenModulo, String nombreModulo,
                                        String estado, double puntaje,
                                        long posicionSegundos, long tiempoAcumulado,
                                        String respuestasParciales) {
        Query q = new Query(new Criteria().andOperator(
            Criteria.where("alumno_id").is(alumnoOid),
            Criteria.where("curso_id").is(cursoOid),
            Criteria.where("orden_modulo").is(ordenModulo)
        ));
        Update upd = new Update()
            .set("alumno_id",            alumnoOid)
            .set("curso_id",             cursoOid)
            .set("orden_modulo",         ordenModulo)
            .set("nombre_modulo",        nombreModulo)
            .set("estado",               estado)
            .set("puntaje_obtenido",     puntaje)
            .set("posicion_segundos",    posicionSegundos)
            .set("tiempo_acumulado",     tiempoAcumulado)
            .set("respuestas_parciales", respuestasParciales)
            .set("fecha_ultima_actividad", Instant.now().toString())
            .inc("intentos", 1);
        mongoTemplate.upsert(q, upd, "progreso_modulos");
    }

    public long contarModulosCompletados(ObjectId alumnoOid, ObjectId cursoOid) {
        return mongoTemplate.count(
            new Query(new Criteria().andOperator(
                Criteria.where("alumno_id").is(alumnoOid),
                Criteria.where("curso_id").is(cursoOid),
                Criteria.where("estado").is("completado")
            )),
            "progreso_modulos"
        );
    }

    // Evaluaciones
    public Document persistirCorreccion(String alumnoId, String cursoId,
                                        String instructorId, double puntaje,
                                        String comentario, String trabajoJson) {
        Document doc = new Document()
            .append("alumno_id",        new ObjectId(alumnoId))
            .append("curso_id",         new ObjectId(cursoId))
            .append("instructor_id",    instructorId)
            .append("puntaje",          puntaje)
            .append("comentario",       comentario)
            .append("trabajo_json",     trabajoJson)
            .append("fecha_correccion", Instant.now().toString())
            .append("estado",           "corregido");
        return mongoTemplate.insert(doc, "evaluaciones");
    }
}
