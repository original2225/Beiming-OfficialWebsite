param(
  [int]$FrontendPort = 5173,
  [int]$ApiPort = 8787,
  [int]$ResourcePort = 8791,
  [int]$AuthPort = 8792,
  [int]$ProfilePort = 8793
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Get-Content (Join-Path $root ".env") -ErrorAction SilentlyContinue | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
  $index = $line.IndexOf("=")
  $key = $line.Substring(0, $index).Trim()
  $value = $line.Substring($index + 1).Trim().Trim('"').Trim("'")
  [Environment]::SetEnvironmentVariable($key, $value, "Process")
}
$env:API_PORT = "$ApiPort"
$env:RESOURCE_SERVICE_PORT = "$ResourcePort"
$env:AUTH_SERVICE_PORT = "$AuthPort"
$env:PROFILE_SERVICE_PORT = "$ProfilePort"
$env:RESOURCE_SERVICE_URL = "http://127.0.0.1:$ResourcePort"
$env:AUTH_SERVICE_URL = "http://127.0.0.1:$AuthPort"
$env:PROFILE_SERVICE_URL = "http://127.0.0.1:$ProfilePort"

function Start-DbTunnelIfNeeded {
  if ($env:AUTH_DB_TUNNEL -eq "0" -or $env:AUTH_DB_TUNNEL -eq "false") { return }

  $localHost = if ($env:AUTH_DB_TUNNEL_LOCAL_HOST) { $env:AUTH_DB_TUNNEL_LOCAL_HOST } else { "127.0.0.1" }
  $localPort = if ($env:AUTH_DB_TUNNEL_LOCAL_PORT) { [int]$env:AUTH_DB_TUNNEL_LOCAL_PORT } else { 15432 }
  $dbUrl = "$env:AUTH_DB_URL $env:DATABASE_URL"
  if (-not $dbUrl.Contains("${localHost}:${localPort}")) { return }

  $listener = Get-NetTCPConnection -LocalAddress $localHost -LocalPort $localPort -State Listen -ErrorAction SilentlyContinue
  if ($listener) {
    Write-Host "Database tunnel already listening on ${localHost}:${localPort}"
    return
  }

  $remoteHost = if ($env:AUTH_DB_TUNNEL_REMOTE_HOST) { $env:AUTH_DB_TUNNEL_REMOTE_HOST } else { "127.0.0.1" }
  $remotePort = if ($env:AUTH_DB_TUNNEL_REMOTE_PORT) { $env:AUTH_DB_TUNNEL_REMOTE_PORT } else { "5432" }
  $sshTarget = if ($env:AUTH_DB_TUNNEL_SSH_TARGET) { $env:AUTH_DB_TUNNEL_SSH_TARGET } else { "root@192.168.1.5" }
  Write-Host "Starting database tunnel ${localHost}:${localPort} -> ${sshTarget}:${remoteHost}:${remotePort}"
  Start-Process -FilePath "ssh" -ArgumentList @("-N", "-L", "${localHost}:${localPort}:${remoteHost}:${remotePort}", $sshTarget) -WindowStyle Hidden | Out-Null

  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 250
    $listener = Get-NetTCPConnection -LocalAddress $localHost -LocalPort $localPort -State Listen -ErrorAction SilentlyContinue
    if ($listener) { return }
  }

  Write-Warning "Database tunnel did not become ready on ${localHost}:${localPort}; services will still start."
}

function Start-BeimingProcess {
  param(
    [string]$Title,
    [string]$WorkingDirectory,
    [string]$Command
  )
  Start-Process powershell `
    -WorkingDirectory $WorkingDirectory `
    -ArgumentList @("-NoExit", "-Command", "[Console]::Title='$Title'; $Command") `
    -WindowStyle Normal | Out-Null
}

Start-DbTunnelIfNeeded
Start-BeimingProcess "Beiming Auth Service :$AuthPort" (Join-Path $root "backend/auth-service") "mvn spring-boot:run"
Start-BeimingProcess "Beiming Resource Service :$ResourcePort" (Join-Path $root "backend/resource-service") "mvn spring-boot:run"
Start-BeimingProcess "Beiming Profile Service :$ProfilePort" (Join-Path $root "backend/profile-service") "mvn spring-boot:run"
Start-BeimingProcess "Beiming API Gateway :$ApiPort" (Join-Path $root "backend/api-gateway") "mvn spring-boot:run"
Start-BeimingProcess "Beiming React Frontend :$FrontendPort" $root "npm run dev -- --port $FrontendPort"

Write-Host "Beiming microservices are starting."
Write-Host "Frontend:       http://127.0.0.1:$FrontendPort"
Write-Host "API Gateway:    http://127.0.0.1:$ApiPort"
Write-Host "Auth:           http://127.0.0.1:$AuthPort"
Write-Host "Resource:       http://127.0.0.1:$ResourcePort"
Write-Host "Profile:        http://127.0.0.1:$ProfilePort"
