# Carga datos de prueba coherentes en Redis usando el .txt provisto.
# El archivo trae directamente comandos HSET / EXPIRE / ZADD / LPUSH / SADD.
# Uso:  .\scripts\load_redis.ps1 [-DataFile "..\archivosUsados\redis_datos_prueba_coherentes.txt"]

param(
    [string]$DataFile = ".\redis_datos_prueba_coherentes.txt",
    [string]$Container = "redis_poliglota"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DataFile)) {
    throw "No existe el archivo de datos Redis: $DataFile"
}

Write-Host "Filtrando comandos Redis del archivo..." -ForegroundColor Cyan
# El .txt incluye comentarios (líneas que empiezan con -- o ==) y separadores.
# Nos quedamos solo con líneas que parecen comandos Redis (HSET, ZADD, etc.).
$cmds = Get-Content $DataFile | Where-Object {
    $_ -match '^(HSET|EXPIRE|ZADD|LPUSH|SADD|SET|HSETNX|HMSET|RPUSH)\b'
}

Write-Host "Total comandos a ejecutar: $($cmds.Count)" -ForegroundColor Cyan

$tmpFile = New-TemporaryFile
$cmds | Set-Content -Path $tmpFile -Encoding utf8

# Copia al contenedor y ejecuta con redis-cli en modo pipe
docker cp $tmpFile.FullName "${Container}:/tmp/redis_load.txt"
docker exec $Container sh -c "redis-cli < /tmp/redis_load.txt"

Remove-Item $tmpFile -Force
Write-Host "Carga Redis OK." -ForegroundColor Green
