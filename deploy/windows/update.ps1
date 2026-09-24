# Download the latest built release of MLAICC Manager from GitHub and swap
# it into this folder if it's different from what's here. Windows
# counterpart of deploy/download-deploy.sh, run by start.ps1 before every
# launch — but can also be run manually:
#
#   powershell -ExecutionPolicy Bypass -File update.ps1 [-Force]
#     -Force   redeploy even if the downloaded bundle is unchanged
#
# Reuses the same mlaiccmanager-dist.tar.gz the Linux container pulls
# (Windows 10+ ships tar.exe). Only mlaiccmanager.jar, static\ and
# disciplines.json are replaced; data.json, competition.json, teams,
# relays, logs\ and backups are never touched. The bundled jre\ is not
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

    if (-not (Test-Path (Join-Path $Extract 'mlaiccmanager.jar'))) {
        throw 'Downloaded bundle has no mlaiccmanager.jar'
    }

    Log "Deploying to $RunDir"
    $Static = Join-Path $RunDir 'static'
    if (Test-Path $Static) { Remove-Item -Path $Static -Recurse -Force }
    Copy-Item -Path (Join-Path $Extract 'static') -Destination $Static -Recurse
    Copy-Item -Path (Join-Path $Extract 'disciplines.json') -Destination $RunDir -Force
    Copy-Item -Path (Join-Path $Extract 'mlaiccmanager.jar') -Destination $RunDir -Force
    Set-Content -Path $StateFile -Value $NewSha -NoNewline -Encoding Ascii
    Log "Deployed build $NewSha"
} catch {
    Log "WARNING: update skipped, starting the current version ($($_.Exception.Message))"
} finally {
    Remove-Item -Path $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
