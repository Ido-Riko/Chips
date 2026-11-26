param(
    [switch]$Yes,
    [switch]$PurgeData,
    [switch]$RemoveTailscale,
    [switch]$RemoveUbuntu,
    [switch]$RemoveWsl
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
    $global:LASTEXITCODE = 0
    & $Action
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "Step failed with exit code $LASTEXITCODE."
    }
}

function Get-TailscaleExecutable {
    $command = Get-Command tailscale.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidate = Join-Path $env:ProgramFiles "Tailscale\tailscale.exe"
    if (Test-Path $candidate) { return $candidate }
    return $null
}

function Confirm-OptionalRemoval {
    param([string]$Title, [switch]$ExplicitlyRequested)
    if ($ExplicitlyRequested) {
        Write-Host "Explicitly requested."
        return $true
    }
    Write-Host ""
    Write-Host "[$Title]"
    return (Read-Host "Remove this shared component? [y/N]") -in @("y", "Y", "yes", "YES")
}

if (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
    $installedDistros = @(wsl.exe --list --quiet | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
} else {
    $installedDistros = @()
}
if ($installedDistros -contains $Distro) {
    $flag = if ($PurgeData) { " --purge-data" } else { "" }
    if ($Yes) { $flag += " --yes" }
    Invoke-Step "Remove Chips services in Ubuntu (preserving data unless -PurgeData is supplied)" {
        wsl.exe -d $Distro -- bash -lc ('cd "$HOME/Chips/chips-backend" && bash deploy/uninstall-pc.sh' + $flag)
    }
} else {
    Write-Host "Ubuntu WSL is not installed; skipping Linux service removal."
}

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Invoke-Step "Remove the Chips WSL auto-start task" {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
}

$tailscale = Get-TailscaleExecutable
if ($tailscale) {
    Invoke-Step "Remove only the Chips Tailscale Serve rule on port 8443" {
        & $tailscale serve --https=8443 off
    }
} else {
    Write-Host "Windows Tailscale is not installed; no Serve rule was changed."
}

if ($installedDistros -contains $Distro) {
    $linuxTailscale = wsl.exe -d $Distro -- bash -lc "command -v tailscale >/dev/null 2>&1"
    if ($LASTEXITCODE -eq 0 -and (Confirm-OptionalRemoval "Remove Tailscale inside Ubuntu" -ExplicitlyRequested:$RemoveTailscale)) {
        Invoke-Step "Uninstall the Ubuntu Tailscale package" {
            wsl.exe -d $Distro -- bash -lc "sudo apt-get remove -y tailscale"
        }
    } elseif ($LASTEXITCODE -ne 0) {
        Write-Host "Tailscale is not installed inside Ubuntu."
    }
}

if ($tailscale -and (Confirm-OptionalRemoval "Remove Tailscale for Windows" -ExplicitlyRequested:$RemoveTailscale)) {
    Invoke-Step "Uninstall Tailscale for Windows" {
        winget.exe uninstall --id Tailscale.Tailscale --exact
    }
} elseif (-not $tailscale) {
    Write-Host "Tailscale for Windows is not installed."
}

if ($installedDistros -contains $Distro) {
    if (Confirm-OptionalRemoval "Permanently unregister the Ubuntu distribution (all files inside it will be deleted)" -ExplicitlyRequested:$RemoveUbuntu) {
        if (-not $RemoveUbuntu) {
            $confirmation = Read-Host "Type '$Distro' to confirm permanent removal"
            if ($confirmation -cne $Distro) {
                Write-Host "Ubuntu was not removed."
            } else {
                Invoke-Step "Permanently unregister Ubuntu from WSL" {
                    wsl.exe --unregister $Distro
                }
            }
        } else {
            Invoke-Step "Permanently unregister Ubuntu from WSL" {
                wsl.exe --unregister $Distro
            }
        }
    }
} else {
    Write-Host "Ubuntu WSL is not installed."
}

if ((Get-Command wsl.exe -ErrorAction SilentlyContinue) -and
    (Confirm-OptionalRemoval "Uninstall the WSL platform (Windows optional features remain enabled)" -ExplicitlyRequested:$RemoveWsl)) {
    Invoke-Step "Uninstall the WSL platform" {
        wsl.exe --uninstall
    }
}

Write-Host ""
Write-Host "Windows optional features remain enabled. Shared Ollama installations and models were not removed."
