param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9-]+$')]
    [string]$RunName,
    [ValidateSet('smoke', 'load', 'hot-wallet')]
    [string]$Profile = 'load',
    [string]$ComposeProject = 'phase2-c00b',
    [string]$K6Image = 'grafana/k6@sha256:3d8e5c7f1d53ec59408c2ffbd9943602a0985ffcbd4a2430ec00f3ffca89796e'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runDir = Join-Path $repoRoot "benchmark\evidence\C00-B-redo\$RunName"
if (Test-Path -LiteralPath $runDir) {
    throw "Run directory already exists: $runDir"
}
New-Item -ItemType Directory -Path $runDir | Out-Null

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker command failed ($LASTEXITCODE): docker $($Arguments -join ' ')"
    }
}

function Reset-TestDatabase {
    $resetSql = @'
DROP DATABASE IF EXISTS digitalwallet_db;
CREATE DATABASE digitalwallet_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
'@
    $resetSql | & docker exec -i dw-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
    if ($LASTEXITCODE -ne 0) { throw 'Failed to recreate benchmark database' }
    Get-Content -LiteralPath (Join-Path $repoRoot 'digitalwallet-database\digitalwallet_p1.sql') -Raw |
        & docker exec -i dw-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" digitalwallet_db'
    if ($LASTEXITCODE -ne 0) { throw 'Failed to load Phase 1 schema' }
}

Push-Location $repoRoot
try {
    Invoke-Docker @('compose', '-p', $ComposeProject, 'up', '-d', '--wait')
    Reset-TestDatabase

    $metadata = [ordered]@{
        runName = $RunName
        profile = $Profile
        baseSha = (& git rev-parse HEAD).Trim()
        dirtyWorktree = [bool](& git status --porcelain)
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
        os = [System.Environment]::OSVersion.VersionString
        cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name).Trim()
        logicalProcessors = [System.Environment]::ProcessorCount
        totalMemoryBytes = [int64](Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
        dockerServer = (& docker version --format '{{.Server.Version}}').Trim()
        k6Image = $K6Image
        composeProject = $ComposeProject
        topology = 'SUT, MySQL, and Dockerized k6 are separate containers on one Docker Desktop host; k6 calls the app directly on the Compose bridge network.'
        limitation = 'Shared-host contention means this is a reproducible local baseline, not an isolated capacity result.'
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDir 'metadata.json') -Encoding utf8

    $samplerOut = Join-Path $runDir 'sampler.csv'
    $samplerStdout = Join-Path $runDir 'sampler.stdout.log'
    $samplerStderr = Join-Path $runDir 'sampler.stderr.log'
    $samplerArgs = @(
        (Join-Path $repoRoot 'digitalwallet-stress-test\monitor\sampler.py'),
        '--interval', '2', '--out', $samplerOut,
        '--docker-containers', 'dw-app', 'dw-mysql',
        '--mysql-container', 'dw-mysql'
    )
    $sampler = Start-Process -FilePath python -ArgumentList $samplerArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $samplerStdout -RedirectStandardError $samplerStderr

    $containerRunDir = "/work/benchmark/evidence/C00-B-redo/$RunName"
    $dockerArgs = @(
        'run', '--rm',
        '--network', "${ComposeProject}_default",
        '-v', "${repoRoot}:/work",
        '-w', '/work/digitalwallet-stress-test/k6',
        '-e', "PROFILE=$Profile",
        '-e', 'BASE_URL=http://app:8080',
        $K6Image, 'run',
        '--out', "csv=$containerRunDir/k6_raw.csv",
        '--summary-export', "$containerRunDir/k6_summary.json",
        '--quiet', '/work/digitalwallet-stress-test/k6/main.js'
    )

    try {
        & docker @dockerArgs 2>&1 | Tee-Object -FilePath (Join-Path $runDir 'k6.log')
        $k6Exit = $LASTEXITCODE
    }
    finally {
        if ($sampler -and -not $sampler.HasExited) {
            Stop-Process -Id $sampler.Id
            $sampler.WaitForExit()
        }
    }

    & docker logs --since $metadata.startedAt dw-app 2>&1 |
        Set-Content -LiteralPath (Join-Path $runDir 'app.log') -Encoding utf8
    & docker logs --since $metadata.startedAt dw-mysql 2>&1 |
        Set-Content -LiteralPath (Join-Path $runDir 'mysql.log') -Encoding utf8

    Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reconcile.sql') -Raw |
        & docker exec -i dw-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -B digitalwallet_db' |
        Set-Content -LiteralPath (Join-Path $runDir 'reconciliation.tsv') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Database reconciliation query failed' }

    & python (Join-Path $PSScriptRoot 'summarize_run.py') --run-dir $runDir --k6-exit $k6Exit
    if ($LASTEXITCODE -ne 0) { throw 'Run summarization failed' }

    $metadata.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $metadata.k6ExitCode = $k6Exit
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDir 'metadata.json') -Encoding utf8
    exit $k6Exit
}
finally {
    Pop-Location
}
