param([switch]$Refresh, [switch]$InspectMetadata, [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pythonExe = & $Python -c "import sys, bs4; assert sys.version_info >= (3, 12); print(sys.executable)"
if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
    throw 'Activate se214-crawler and install tools/crawl/requirements.txt before launching.'
}
$runId = [guid]::NewGuid().ToString('N')
$logs = Join-Path $repo '.data/collection/runs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$arguments = @('-B', 'tools/crawl/run.py', '--run-id', $runId)
if ($Refresh) { $arguments += '--refresh' }
if ($InspectMetadata) { $arguments += '--inspect-metadata' }
$process = Start-Process -FilePath $pythonExe -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs "$runId.stdout.log") -RedirectStandardError (Join-Path $logs "$runId.stderr.log")
$job = @{ run_id = $runId; pid = $process.Id; started_at = [DateTime]::UtcNow.ToString('o'); receipt = (Join-Path $logs "$runId.json") }
$job | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logs "$runId.job.json") -Encoding utf8
$job | ConvertTo-Json
