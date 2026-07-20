#Requires -Version 5.1
<#
.SYNOPSIS
  MBUSTER 차단을 줄이기 위해, 일반 Edge를 띄운 뒤 SelectTime을 붙입니다.

.USAGE
  1) 기존 Edge 창을 모두 닫기
  2) 이 스크립트 실행
  3) 열린 Edge에서 auc.or.kr 로그인 (처음 1회)
  4) 같은 창에서 SelectTime doctor / once 가 이어서 동작
#>
$ErrorActionPreference = "Stop"
$Desktop = Split-Path $PSScriptRoot -Parent
Set-Location $Desktop

$edgeCandidates = @(
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
)
$edge = $edgeCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) {
  Write-Error "Microsoft Edge를 찾을 수 없습니다."
}

$profileDir = Join-Path $env:TEMP "selecttime-edge-profile"
New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
$port = 9222
$cdp = "http://127.0.0.1:$port"

Write-Host ""
Write-Host "=== SelectTime (Edge 연결 모드) ===" -ForegroundColor Cyan
Write-Host "1) 열려 있는 Edge를 모두 종료하세요."
Write-Host "2) Enter를 누르면 Edge가 디버깅 모드로 실행됩니다."
Write-Host ""
Read-Host "준비되면 Enter"

# Kill edge only if user confirmed
Get-Process msedge -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

Write-Host "Edge 시작: $edge"
Start-Process -FilePath $edge -ArgumentList @(
  "--remote-debugging-port=$port",
  "--user-data-dir=`"$profileDir`"",
  "--no-first-run",
  "--no-default-browser-check",
  "https://www.auc.or.kr/hgsports/main/view"
)

Write-Host "CDP 대기 중 ($cdp) ..."
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  try {
    $r = Invoke-WebRequest -Uri "$cdp/json/version" -UseBasicParsing -TimeoutSec 2
    if ($r.StatusCode -eq 200) { $ready = $true; break }
  } catch { }
  Start-Sleep -Seconds 1
}
if (-not $ready) {
  Write-Error "Edge 원격 디버깅 포트($port)에 연결하지 못했습니다."
}

# Patch config.local.yaml cdp_url
$configPath = Join-Path $Desktop "config.local.yaml"
if (-not (Test-Path $configPath)) {
  Copy-Item (Join-Path $Desktop "..\shared\config.example.yaml") $configPath
}
$raw = Get-Content $configPath -Raw
if ($raw -match '(?m)^cdp_url:\s*.*$') {
  $raw = [regex]::Replace($raw, '(?m)^cdp_url:\s*.*$', "cdp_url: `"$cdp`"")
} else {
  $raw = $raw.TrimEnd() + "`r`ncdp_url: `"$cdp`"`r`n"
}
Set-Content -Path $configPath -Value $raw -Encoding UTF8

Write-Host ""
Write-Host "Edge가 열렸습니다. 사이트에서 로그인할 수 있으면 Enter를 누르세요." -ForegroundColor Yellow
Write-Host "(로그인 후에 SelectTime doctor가 같은 Edge에 붙습니다)"
Read-Host "로그인 후 Enter"

$env:PYTHONPATH = "src"
$py = Join-Path $Desktop ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
  Write-Error "가상환경이 없습니다. 먼저 .\scripts\setup.ps1 을 실행하세요."
}

Write-Host "SelectTime doctor 실행..." -ForegroundColor Green
& $py -m selecttime doctor
$code = $LASTEXITCODE

Write-Host ""
Write-Host "종료 코드: $code"
Write-Host "다음부터는 같은 Edge가 열린 상태에서:"
Write-Host "  `$env:PYTHONPATH='src'; .\.venv\Scripts\python.exe -m selecttime once"
Write-Host "를 실행하면 됩니다. (config.local.yaml 의 cdp_url 유지)"
exit $code
