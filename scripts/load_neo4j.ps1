# Carga el grafo en Neo4j: copia los CSV al import dir del contenedor y
# ejecuta load_neo4j.cypher.
# Uso:  .\scripts\load_neo4j.ps1 [-DataDir "..\archivosUsados"]

param(
    [string]$DataDir = ".",
    [string]$Container = "neo4j_poliglota",
    [string]$User = "neo4j",
    [string]$Password = "password123"
)

$ErrorActionPreference = "Stop"

$csvs = @("prerrequisitos.csv", "rutas_certificacion.csv")

Write-Host "Copiando CSVs al import dir de Neo4j..." -ForegroundColor Cyan
foreach ($f in $csvs) {
    $src = Join-Path $DataDir $f
    if (-not (Test-Path $src)) {
        Write-Warning "No existe $src - se saltea."
        continue
    }
    docker cp $src "${Container}:/var/lib/neo4j/import/$f"
}

Write-Host "Ejecutando load_neo4j.cypher..." -ForegroundColor Cyan
$cypherFile = Join-Path $PSScriptRoot "load_neo4j.cypher"
Get-Content $cypherFile | docker exec -i $Container cypher-shell -u $User -p $Password

Write-Host "Carga Neo4j OK." -ForegroundColor Green
