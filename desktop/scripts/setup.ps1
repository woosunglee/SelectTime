#Requires -Version 5.1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

if (-not (Test-Path .venv)) {
  python -m venv .venv
}
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt

# Default config uses installed Edge/Chrome via Playwright channel — no Chromium download needed.
# Only install bundled Chromium if you set browser: chromium in config.local.yaml:
#   python -m playwright install chromium

if (-not (Test-Path .\config.local.yaml)) {
  Copy-Item ..\shared\config.example.yaml .\config.local.yaml
}
if (-not (Test-Path .\.env)) {
  Copy-Item .\.env.example .\.env
}
Write-Host "SelectTime desktop setup complete."
Write-Host "Default browser: Microsoft Edge (config browser: msedge). Chrome: browser: chrome"
Write-Host "Activate: .\.venv\Scripts\Activate.ps1"
Write-Host "Run: `$env:PYTHONPATH='src'; python -m selecttime doctor"
