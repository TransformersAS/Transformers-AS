param([switch]$Build)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

function Invoke-DockerChecked {
    param([string[]]$DockerArguments)
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker fallo. Revisa el error anterior.' }
}

Push-Location $projectRoot
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Instala y abre Docker Desktop antes de ejecutar este script.'
    }
    Invoke-DockerChecked -DockerArguments @('info', '--format', '{{.ServerVersion}}')

    # Compose tambien puede obtener las credenciales del archivo .env existente.
    if (-not (Test-Path -LiteralPath '.env')) {
        foreach ($variable in @('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD')) {
            if (-not [Environment]::GetEnvironmentVariable($variable, 'Process')) {
                $secureValue = Read-Host "Introduce $variable (usa la clave existente si ya hay datos)" -AsSecureString
                $plainValue = [System.Net.NetworkCredential]::new('', $secureValue).Password
                if ([string]::IsNullOrWhiteSpace($plainValue)) { throw "$variable no puede estar vacia." }
                [Environment]::SetEnvironmentVariable($variable, $plainValue, 'Process')
                $plainValue = $null
                $secureValue = $null
            }
        }
    }

    $previousProfile = $env:SPRING_PROFILES_ACTIVE
    $env:SPRING_PROFILES_ACTIVE = 'local'
    try {
        Invoke-DockerChecked -DockerArguments @('compose', 'config', '--quiet')
        $startupArguments = @('compose', 'up', '-d', '--wait', '--wait-timeout', '300')
        if ($Build) { $startupArguments += '--build' }
        Invoke-DockerChecked -DockerArguments $startupArguments

        # Readiness incluye MySQL: no se carga el catalogo antes de las migraciones.
        Invoke-DockerChecked -DockerArguments @('compose', 'exec', '-T', 'backend', 'curl',
            '--fail', '--silent', '--show-error', '--retry', '12', '--retry-delay', '5',
            '--retry-all-errors', '--max-time', '5',
            'http://localhost:8080/actuator/health/readiness')

        Write-Host 'Cargando productos de ejemplo...'
        $seed = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'catalogo-local.sql') -Raw -Encoding UTF8
        $seed += [Environment]::NewLine + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fichas-productos-local.sql') -Raw -Encoding UTF8)
        $previousEncoding = $OutputEncoding
        try {
            $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            # Las credenciales se leen dentro del contenedor, sin incluirlas en los argumentos.
            $seed | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 --user="$MYSQL_USER" "$MYSQL_DATABASE"'
            if ($LASTEXITCODE -ne 0) { throw 'No se pudo cargar el catalogo. Revisa el error de MySQL.' }
        } finally { $OutputEncoding = $previousEncoding }

        Invoke-DockerChecked -DockerArguments @('compose', 'ps')
        Write-Host 'Sistema listo. Frontend: http://localhost:4300 (salvo puerto personalizado en .env).'
        Write-Host 'Cuenta local: demo@marketplace.local / MarketplaceDemo123!'
    } finally {
        $env:SPRING_PROFILES_ACTIVE = $previousProfile
    }
} finally { Pop-Location }
