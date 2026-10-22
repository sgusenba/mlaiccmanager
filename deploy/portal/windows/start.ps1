# Portable Windows launcher for the MLAIC registration portal, run via start.bat.
#
# 1. If the portal is already running, just opens the browser.
# 2. Runs update.ps1 to pull the latest build (skipped when a file named
#    "no-auto-update" exists next to this script - create it to freeze the
#    version).
# 3. Starts the portal with the bundled jre\ in this console window and
#    opens the log-in page once it's listening. Closing the window stops it.
#
# The portal listens on http://localhost:8080 only (set PORTAL_PORT for
# another port) and keeps its data in portal-data\ in this folder. That suits
# trying it out or a meet on the local network behind your own proxy; on the
# internet it runs on a Linux machine (deploy/portal/setup.md). Create the
# first organiser account with portal-cli.bat.

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot
$Host.UI.RawUI.WindowTitle = 'MLAIC registration portal'

$Port = if ($env:PORTAL_PORT) { [int]$env:PORTAL_PORT } else { 8080 }
$Url = "http://localhost:$Port/login/"

function Test-Listening {
    try {
        $client = New-Object Net.Sockets.TcpClient('127.0.0.1', $Port)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

if (Test-Listening) {
    Write-Host "The portal is already running, opening $Url"
    Start-Process $Url
    exit 0
}

if (Test-Path (Join-Path $PSScriptRoot 'no-auto-update')) {
    Write-Host 'no-auto-update file found, skipping update check'
} else {
    & (Join-Path $PSScriptRoot 'update.ps1')
}

$Java = Join-Path $PSScriptRoot 'jre\bin\java.exe'
if (-not (Test-Path $Java)) { $Java = 'java' }

# Open the browser as soon as the portal accepts connections
$browserJob = Start-Job -ArgumentList $Port, $Url -ScriptBlock {
    param($port, $url)
    for ($i = 0; $i -lt 120; $i++) {
        try {
            $client = New-Object Net.Sockets.TcpClient('127.0.0.1', $port)
            $client.Close()
            Start-Process $url
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
}

try {
    & $Java -jar mlaic-portal.jar
    $exitCode = $LASTEXITCODE
} finally {
    Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue
}
exit $exitCode
