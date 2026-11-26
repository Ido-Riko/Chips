param(
    [switch]$Yes,
    [switch]$PurgeData
)

$ErrorActionPreference = "Stop"
$Distro = "Ubuntu"
$TaskName = "Chips Ubuntu WSL server"

function Invoke-Step {
    param([string]$Title, [scriptblock]$Action)
    Write-Host ""
    Write-Host "[$Title]"
    if ($Yes) {
        Write-Host "Auto-confirmed (-Yes)."
    } else {
        $answer = Read-Host "Run this step? [y/N]"
        if ($answer -notin @("y", "Y", "yes", "YES")) {
            throw "Stopped by user; no later steps were run."
        }
    }
    & $Action
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "Step failed with exit code $LASTEXITCODE."
    }
}

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "WSL is unavailable."
}
$installedDistros = @(wsl.exe --list --quiet | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
if ($installedDistros -notcontains $Distro) {
    throw "Ubuntu WSL2 is not installed; nothing was removed."
}

$flag = if ($PurgeData) { " --purge-data" } else { "" }
if ($Yes) { $flag += " --yes" }
Invoke-Step "Remove Chips services in Ubuntu (preserving data unless -PurgeData is supplied)" {
    wsl.exe -d $Distro -- bash -lc ('cd "$HOME/Chips/chips-backend" && bash deploy/uninstall-pc.sh' + $flag)
}

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Invoke-Step "Remove the Chips WSL auto-start task" {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
}

Write-Host ""
Write-Host "Ubuntu itself, WSL, Tailscale, Ollama and shared Ollama models were not uninstalled."
