# Download the latest built release of MLAICC Manager from GitHub and swap
# it into this folder if it's different from what's here. Windows
# counterpart of deploy/download-deploy.sh, run by start.ps1 before every
# launch — but can also be run manually:
#
#   powershell -ExecutionPolicy Bypass -File update.ps1 [-Force]
#     -Force   redeploy even if the downloaded bundle is unchanged
#
# Reuses the same mlaiccmanager-dist.tar.gz the Linux container pulls
# (Windows 10+ ships tar.exe). Only mlaiccmanager.jar, static\, the
# shipped defaults (default-disciplines.json, default-rankings.json) and
# the launcher scripts are replaced; data.json, catalog.json, rankings.json,
# teams, relays, logs\ and backups are never touched. The bundled jre\ is not
# updated here — download the full zip again for a newer Java.
#
# Never fails the launch: if offline or anything goes wrong, it prints a
# warning and leaves the current version in place.
#
# Configure via environment variables (optional):
#   MLAICC_RELEASE_URL   Bundle URL to download (default: the "latest" release)

param([switch]$Force)

$ErrorActionPreference = 'Stop'
# The progress bar makes Invoke-WebRequest very slow in Windows PowerShell 5.1
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$ReleaseUrl = if ($env:MLAICC_RELEASE_URL) { $env:MLAICC_RELEASE_URL } else { 'https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-dist.tar.gz' }
$RunDir = $PSScriptRoot
$StateFile = Join-Path $RunDir '.deployed-sha256'
# Call Windows' own bsdtar explicitly: a GNU tar earlier on PATH (Git Bash)
# can't handle C:\ paths
$Tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $Tar)) { $Tar = 'tar' }

function Log($msg) { Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg" }

$TmpDir = Join-Path ([IO.Path]::GetTempPath()) ("mlaiccmanager-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $TmpDir | Out-Null
try {
    $Archive = Join-Path $TmpDir 'dist.tar.gz'
    Log "Checking for updates: $ReleaseUrl"
    Invoke-WebRequest -Uri $ReleaseUrl -OutFile $Archive -TimeoutSec 15 -UseBasicParsing

    $NewSha = (Get-FileHash -Path $Archive -Algorithm SHA256).Hash.ToLower()
    $OldSha = if (Test-Path $StateFile) { (Get-Content -Path $StateFile -Raw).Trim() } else { '' }

    if ($NewSha -eq $OldSha -and -not $Force) {
        Log "Already running this build ($NewSha), nothing to do"
        return
    }

    Log "Extracting"
    $Extract = Join-Path $TmpDir 'extract'
    New-Item -ItemType Directory -Path $Extract | Out-Null
    & $Tar -xzf $Archive -C $Extract
    if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }

    # Everything is checked before anything is replaced: a half-deployed bundle
    # (new static\ next to the old jar) would break the app
    foreach ($Name in 'mlaiccmanager.jar', 'static', 'default-disciplines.json', 'default-rankings.json') {
        if (-not (Test-Path (Join-Path $Extract $Name))) {
            throw "Downloaded bundle has no $Name"
        }
    }

    Log "Deploying to $RunDir"
    Copy-Item -Path (Join-Path $Extract 'mlaiccmanager.jar') -Destination (Join-Path $RunDir 'mlaiccmanager.jar.new') -Force
    $Static = Join-Path $RunDir 'static'
    if (Test-Path $Static) { Remove-Item -Path $Static -Recurse -Force }
    Copy-Item -Path (Join-Path $Extract 'static') -Destination $Static -Recurse
    Copy-Item -Path (Join-Path $Extract 'default-disciplines.json') -Destination $RunDir -Force
    Copy-Item -Path (Join-Path $Extract 'default-rankings.json') -Destination $RunDir -Force
    Move-Item -Path (Join-Path $RunDir 'mlaiccmanager.jar.new') -Destination (Join-Path $RunDir 'mlaiccmanager.jar') -Force
    Set-Content -Path $StateFile -Value $NewSha -NoNewline -Encoding Ascii
    # Keep the launcher scripts up to date: the bundle carries the current ones
    foreach ($Script in 'update.ps1', 'start.ps1', 'start.bat') {
        $Bundled = Join-Path $Extract $Script
        if (Test-Path $Bundled) { Copy-Item -Path $Bundled -Destination $RunDir -Force }
    }
    Log "Deployed build $NewSha"
} catch {
    Log "WARNING: update skipped, starting the current version ($($_.Exception.Message))"
} finally {
    Remove-Item -Path $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
