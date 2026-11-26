param(
    [switch]$Yes
)

$ErrorActionPreference = "Stop"
$Distro = "Ubuntu"
$TaskName = "Chips Ubuntu WSL server"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run PowerShell as Administrator for WSL setup and Tailscale Serve configuration."
}

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
    throw "WSL is unavailable. Use a supported Windows 11 installation and run this script from an elevated PowerShell window."
}

$installedDistros = @(wsl.exe --list --quiet | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
if ($installedDistros -notcontains $Distro) {
    Invoke-Step "Install WSL2 and Ubuntu (Windows may ask to reboot)" {
        wsl.exe --install --distribution $Distro
    }
    Write-Host "If Windows requests a reboot, reboot, complete Ubuntu's first-run username/password setup, then rerun this script."
    exit 0
}

Invoke-Step "Ensure Ubuntu uses WSL2" {
    wsl.exe --set-version $Distro 2
}

Invoke-Step "Enable systemd in the Ubuntu WSL2 distribution" {
    $python = @'
from pathlib import Path
path = Path("/etc/wsl.conf")
lines = path.read_text().splitlines() if path.exists() else []
section = None
for index, line in enumerate(lines):
    value = line.strip()
    if value.startswith("[") and value.endswith("]"):
        section = value[1:-1].lower()
    elif section == "boot" and value.lower().startswith("systemd="):
        lines[index] = "systemd=true"
        break
else:
    for index, line in enumerate(lines):
        if line.strip().lower() == "[boot]":
            lines.insert(index + 1, "systemd=true")
            break
    else:
        lines.extend(["", "[boot]", "systemd=true"])
path.write_text("\n".join(lines) + "\n")
'@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($python))
    wsl.exe -d $Distro -- bash -lc "echo $encoded | base64 -d | sudo python3"
    if ($LASTEXITCODE -ne 0) { throw "Could not enable WSL systemd." }
    wsl.exe --terminate $Distro
}

Invoke-Step "Install Git in Ubuntu" {
    wsl.exe -d $Distro -- bash -lc "sudo apt-get update && sudo apt-get install -y git"
}

Invoke-Step "Clone Chips into the Linux home directory if it is not already present" {
    wsl.exe -d $Distro -- bash -lc 'if [ ! -d "$HOME/Chips/.git" ]; then git clone https://github.com/Ido-Riko/Chips.git "$HOME/Chips"; else echo "Keeping existing $HOME/Chips checkout; no pull performed."; fi'
}

function Get-TailscaleExecutable {
    $command = Get-Command tailscale.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidate = Join-Path $env:ProgramFiles "Tailscale\tailscale.exe"
    if (Test-Path $candidate) { return $candidate }
    return $null
}

$tailscale = Get-TailscaleExecutable
if (-not $tailscale) {
    Invoke-Step "Install the Windows Tailscale app using winget" {
        if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
            throw "winget is unavailable. Install Tailscale for Windows from its official download page, then rerun."
        }
        winget.exe install --id Tailscale.Tailscale --exact --accept-package-agreements --accept-source-agreements
    }
    $tailscale = Get-TailscaleExecutable
    if (-not $tailscale) { throw "Tailscale installed, but tailscale.exe was not found. Restart PowerShell and rerun." }
}

$tailscaleOutput = & $tailscale status --json 2>$null
$tailscaleStatus = $null
if ($LASTEXITCODE -eq 0 -and $tailscaleOutput) {
    $tailscaleStatus = $tailscaleOutput | ConvertFrom-Json
}
if (-not $tailscaleStatus -or $tailscaleStatus.BackendState -ne "Running") {
    Invoke-Step "Connect the Windows host to Tailscale (complete browser sign-in if prompted)" {
        & $tailscale up
    }
}
$tailscaleOutput = & $tailscale status --json 2>$null
if ($LASTEXITCODE -ne 0 -or -not $tailscaleOutput) {
    throw "Tailscale did not become available after sign-in."
}
$tailscaleStatus = $tailscaleOutput | ConvertFrom-Json
if ($tailscaleStatus.BackendState -ne "Running") { throw "Tailscale is not connected." }

Invoke-Step "Run the Linux provisioning steps inside Ubuntu" {
    $flag = if ($Yes) { " --yes" } else { "" }
    $command = 'cd "$HOME/Chips/chips-backend" && bash deploy/provision-pc.sh --wsl' + $flag
    wsl.exe -d $Distro -- bash -lc $command
}

Invoke-Step "Configure private Tailscale HTTPS to proxy to the WSL API" {
    & $tailscale serve --bg --https=8443 http://127.0.0.1:8000
}

$tailscaleStatus = (& $tailscale status --json | ConvertFrom-Json)
$dnsName = $tailscaleStatus.Self.DNSName.TrimEnd(".")
if (-not $dnsName) { throw "Tailscale did not provide this PC's DNS name." }
Invoke-Step "Verify the private HTTPS health endpoint" {
    $health = Invoke-RestMethod -Uri "https://${dnsName}:8443/health" -TimeoutSec 10
    if ($health.status -ne "ok") { throw "Unexpected health response." }
}

Invoke-Step "Start Ubuntu automatically at Windows sign-in" {
    $action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\wsl.exe" -Argument "-d $Distro --exec /bin/true"
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Description "Start the Chips WSL2 server after sign-in." -Force | Out-Null
}

Write-Host ""
Write-Host "WSL2 provisioning finished. On the phone, use https://${dnsName}:8443/api/v1 and the token in Ubuntu's ~/Chips/chips-backend/.runtime/phone-enrollment.json."
