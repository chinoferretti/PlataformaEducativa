# Carga los datos del dominio en MongoDB usando mongoimport dentro del contenedor.
# Requisitos: docker-compose levantado (mongo_poliglota corriendo).
# Uso:  .\scripts\load_mongo.ps1 [-DataDir "..\archivosUsados"]

param(
    [string]$DataDir = ".",
    [string]$Container = "mongo_poliglota",
    [string]$Database = "plataforma_educativa"
)

$ErrorActionPreference = "Stop"

$colecciones = @(
    @{ file = "alumnos.json";           coll = "alumnos" },
    @{ file = "instructores.json";      coll = "instructores" },
    @{ file = "cursos.json";            coll = "cursos" },
    @{ file = "inscripciones.json";     coll = "inscripciones" },
    @{ file = "progreso_modulos.json";  coll = "progreso_modulos" },
    @{ file = "certificados.json";      coll = "certificados" }
)

Write-Host "Copiando archivos JSON al contenedor $Container..." -ForegroundColor Cyan
foreach ($c in $colecciones) {
    $src = Join-Path $DataDir $c.file
    if (-not (Test-Path $src)) {
        Write-Warning "No existe $src - se saltea."
        continue
    }
    docker cp $src "${Container}:/tmp/$($c.file)"
}

Write-Host "Importando colecciones (con --drop, idempotente)..." -ForegroundColor Cyan
foreach ($c in $colecciones) {
    Write-Host "  -> $($c.coll)" -ForegroundColor Yellow
    docker exec $Container mongoimport `
        --db $Database `
        --collection $c.coll `
        --file "/tmp/$($c.file)" `
        --jsonArray `
        --drop
}

Write-Host "Carga MongoDB OK." -ForegroundColor Green
