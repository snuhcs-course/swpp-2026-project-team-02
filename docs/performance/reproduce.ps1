param(
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][string]$OutRoot,
    [ValidateSet('offline','live')][string]$Mode = 'offline',
    [string]$CaseId,
    [string]$Python = (Join-Path $PSScriptRoot '.venv\Scripts\python.exe')
)
$ErrorActionPreference = 'Stop'
foreach ($required in @($Repo, $Python, (Join-Path $PSScriptRoot 'task3\task3_measure.py'), (Join-Path $PSScriptRoot 'task3\cases.json'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing required path: $required" }
}
$repoPath = (Resolve-Path -LiteralPath $Repo).Path
if (-not (Test-Path -LiteralPath (Join-Path $repoPath 'backend\api_server.py'))) { throw 'Target backend source is missing.' }
$commit = & git -C $repoPath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit.Trim() -ne 'd706ad2ea66aaed6faeebea1c21dc358ace0ecf2') { throw 'Wrong repository commit.' }
$changes = & git -C $repoPath diff HEAD --name-only
if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Cannot verify clean tracked files.' }
if (Test-Path -LiteralPath $OutRoot) { throw 'Output path exists. Choose a new output root.' }
$items = @(Import-Csv -LiteralPath (Join-Path $PSScriptRoot 'case_manifest.csv'))
if ($CaseId) {
    $items = @($items | Where-Object { $_.case_id -eq $CaseId })
    if ($items.Count -ne 1) { throw 'Unknown case_id. See case_manifest.csv.' }
}
if ($Mode -eq 'live' -and -not $env:GEMINI_API_KEY) { throw 'Set your own GEMINI_API_KEY in this session first.' }
$measure = Join-Path $PSScriptRoot 'task3\task3_measure.py'
$cases = Join-Path $PSScriptRoot 'task3\cases.json'
if ($Mode -eq 'offline') {
    $measureArgs = @($measure, '--repo', $repoPath, '--cases', $cases, '--mode', 'offline', '--out', $OutRoot, '--seed', '20261014')
    if ($CaseId) { $measureArgs += @('--case-id', $CaseId) }
    & $Python @measureArgs
    if ($LASTEXITCODE -ne 0) { throw 'Offline harness failed.' }
    Write-Host 'Inspect offline_summary.json and suite logs: harness exit 0 alone does not mean every suite passed.'
} else {
    New-Item -ItemType Directory -Path $OutRoot | Out-Null
    foreach ($item in $items) {
        $caseOut = Join-Path $OutRoot $item.folder
        & $Python $measure --repo $repoPath --cases $cases --out $caseOut --mode live --repeats 5 --seed 20261014 --case-id $item.case_id
        if ($LASTEXITCODE -ne 0) { throw "Harness process failed for $($item.case_id). Inspect logs before continuing." }
        $summary = Get-Content -LiteralPath (Join-Path $caseOut 'execution_summary.json') -Raw | ConvertFrom-Json
        if ($summary.stop_reason) { throw 'Credential, model or quota stop reason recorded. Remaining cases were not started.' }
    }
    Write-Host 'Completed case-by-case execution. Inspect outcomes and constraints before drawing conclusions.'
}
