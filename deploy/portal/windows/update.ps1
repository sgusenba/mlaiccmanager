# Updates the registration portal in this folder to the latest build, if
# there is one. Windows counterpart of deploy/portal/portal-update.sh, run by
# start.ps1 before every start, and can be run by hand:
#
#   powershell -ExecutionPolicy Bypass -File update.ps1            check and update
#   powershell -ExecutionPolicy Bypass -File update.ps1 -Rollback  back to the previous jar, and stop updating
#
# It downloads SHA256SUMS from the "portal-latest" release and compares it
# with what is here. A new jar is downloaded, checked against its checksum,
# test-run (--version) and only then put in place; the old one is kept as
# mlaic-portal.jar.prev. update.ps1, start.ps1 and portal-cli.bat are updated
# the same way. portal-data\ is never touched, and neither is the bundled
# jre\: download the zip again for a newer Java.
#
# Never fails the start: when offline, or when anything goes wrong, it prints
# a warning and leaves the current version in place.
#
# Settings (environment, optional):
#   PORTAL_RELEASE_URL   default https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest

param([switch]$Rollback)

$ErrorActionPreference = 'Stop'
# The progress bar makes Invoke-WebRequest very slow in Windows PowerShell 5.1
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$ReleaseUrl = if ($env:PORTAL_RELEASE_URL) { $env:PORTAL_RELEASE_URL.TrimEnd('/') } else { 'https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest' }
$RunDir = $PSScriptRoot
$Jar = Join-Path $RunDir 'mlaic-portal.jar'
$Java = Join-Path $RunDir 'jre\bin\java.exe'
if (-not (Test-Path $Java)) { $Java = 'java' }
# Scripts this one keeps up to date (itself included). Not start.bat: cmd reads
# it line by line while it runs, so replacing it would break the running start
$Scripts = 'update.ps1', 'start.ps1', 'portal-cli.bat'

function Log($msg) { Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] [portal-update] $msg" }

function Get-Sha256($path) {
    if (Test-Path $path) { (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower() } else { '' }
}

# "mlaic-portal 1.0.0 (commit ..., built ...)", or '' when the jar does not run
function Get-JarVersion($path) {
    # Under 'Stop', Windows PowerShell 5.1 turns any line Java writes to stderr into an error
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Java -jar $path --version 2>$null
        if ($LASTEXITCODE -eq 0 -and $out) { return ($out | Select-Object -First 1).Trim() }
    } catch {
        # does not run: no version
    }
    return ''
}

if ($Rollback) {
    $Prev = "$Jar.prev"
    if (-not (Test-Path $Prev)) {
        Log "No previous version ($Prev)"
        exit 1
    }
    Move-Item -Path $Prev -Destination $Jar -Force
    New-Item -ItemType File -Path (Join-Path $RunDir 'no-auto-update') -Force | Out-Null
    Log "Back to the previous version: $(Get-JarVersion $Jar)"
    Log "Automatic updates are off until the file no-auto-update in $RunDir is deleted"
    exit 0
}

$TmpDir = Join-Path ([IO.Path]::GetTempPath()) ("mlaic-portal-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $TmpDir | Out-Null
try {
    Log "Checking for updates: $ReleaseUrl"
    $SumsFile = Join-Path $TmpDir 'SHA256SUMS'
    Invoke-WebRequest -Uri "$ReleaseUrl/SHA256SUMS" -OutFile $SumsFile -TimeoutSec 15 -UseBasicParsing

    # File name -> checksum, from lines "<sha256>  <name>" (or "*<name>")
    $Listed = @{}
    foreach ($line in Get-Content -Path $SumsFile) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$') { $Listed[$Matches[2]] = $Matches[1].ToLower() }
    }

    # Downloads NAME into the temporary folder and checks it; returns its path, or $null on a mismatch
    function Get-Checked($name) {
        $target = Join-Path $TmpDir $name
        Invoke-WebRequest -Uri "$ReleaseUrl/$name" -OutFile $target -TimeoutSec 300 -UseBasicParsing
        if ((Get-Sha256 $target) -ne $Listed[$name]) {
            Log "Checksum of $name does not match: not installed"
            return $null
        }
        return $target
    }

    $Want = $Listed['mlaic-portal.jar']
    if (-not $Want) {
        Log 'SHA256SUMS lists no mlaic-portal.jar; starting the installed version'
        return
    }

    if ($Want -eq (Get-Sha256 $Jar)) {
        Log "Up to date: $(Get-JarVersion $Jar)"
    } else {
        $NewJar = Get-Checked 'mlaic-portal.jar'
        $NewVersion = if ($NewJar) { Get-JarVersion $NewJar } else { '' }
        if ($NewVersion) {
            $OldVersion = if (Test-Path $Jar) { Get-JarVersion $Jar } else { 'none' }
            if (Test-Path $Jar) { Copy-Item -Path $Jar -Destination "$Jar.prev" -Force }
            Copy-Item -Path $NewJar -Destination "$Jar.new" -Force
            Move-Item -Path "$Jar.new" -Destination $Jar -Force
            Log "Updated: $OldVersion -> $NewVersion"
        } else {
            Log 'The new build could not be downloaded or does not run; starting the installed version'
        }
    }

    foreach ($Script in $Scripts) {
        $Want = $Listed[$Script]
        $Here = Join-Path $RunDir $Script
        if ($Want -and $Want -ne (Get-Sha256 $Here)) {
            $New = Get-Checked $Script
            if ($New) {
                Copy-Item -Path $New -Destination $Here -Force
                Log "Updated $Script"
            }
        }
    }
} catch {
    Log "WARNING: update skipped, starting the installed version ($($_.Exception.Message))"
} finally {
    Remove-Item -Path $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
