# Portable Windows launcher for MLAICC Manager, run via start.bat.
#
# 1. If the app is already running, just opens the browser.
# 2. Runs update.ps1 to pull the latest build (skipped when a file named
#    "no-auto-update" exists next to this script — create it to freeze the
#    version, e.g. during a competition).
# 3. Starts the server with the bundled jre\ in this console window and
#    opens the browser once it's listening. Closing the window stops it.
#
# All data (data.json, competition.json, logs\, ...) lives in this folder,
# since the app resolves every path against its working directory.

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot
$Host.UI.RawUI.WindowTitle = 'MLAICC Manager'

$Port = 5000
$Url = "http://localhost:$Port/"

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
    Write-Host "MLAICC Manager is already running, opening $Url"
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

# Open the browser as soon as the server accepts connections
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
    & $Java -jar mlaiccmanager.jar
    $exitCode = $LASTEXITCODE
} finally {
    Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue
}
exit $exitCode
