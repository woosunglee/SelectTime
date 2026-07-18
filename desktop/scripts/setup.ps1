#Requires -Version 5.1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

if (-not (Test-Path .venv)) {
  python -m venv .venv
}
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
python -m playwright install chromium
if (-not (Test-Path .\config.local.yaml)) {
  Copy-Item ..\shared\config.example.yaml .\config.local.yaml
}
if (-not (Test-Path .\.env)) {
  Copy-Item .\.env.example .\.env
}
Write-Host "SelectTime desktop setup complete."
Write-Host "Activate: .\.venv\Scripts\Activate.ps1"
Write-Host "Run: `$env:PYTHONPATH='src'; python -m selecttime doctor"
