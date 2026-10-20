[CmdletBinding()]
param(
    [switch]$Upload,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9.-]*$')]
    [string]$ServerIP,
    [ValidatePattern('^[a-z_][a-z0-9_-]*$')]
    [string]$User = 'idori',
    [ValidatePattern('^/[A-Za-z0-9_./-]+$')]
    [string]$RemoteDir = '/home/idori/chips-backend',
    [string]$PythonCommand = 'python',
    [string]$OutputArchive = (Join-Path $PSScriptRoot 'dist/chips-backend.tar.gz')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Upload -and [string]::IsNullOrWhiteSpace($ServerIP)) {
    throw '-Upload requires -ServerIP (a host name or IPv4 address).'
}
if ($RemoteDir.Split('/') -contains '..') {
    throw 'RemoteDir must not contain parent-directory traversal.'
}
$archive = [System.IO.Path]::GetFullPath($OutputArchive)
Push-Location $PSScriptRoot
try {
    & $PythonCommand -m app.scripts.package_release --output $archive
    if ($LASTEXITCODE -ne 0) { throw "Packaging failed (exit $LASTEXITCODE). Nothing uploaded." }
} finally {
    Pop-Location
}

if ($Upload) {
    # Transfer source only. Existing server secrets, database and service stay untouched.
    & scp -O -- $archive "${User}@${ServerIP}:${RemoteDir}/"
    if ($LASTEXITCODE -ne 0) { throw "Upload failed (exit $LASTEXITCODE). Deployment is not complete." }
    Write-Host 'Archive uploaded. Follow DEPLOYMENT.md on the server to install and validate it.'
} else {
    Write-Host 'Offline package ready. Use -Upload -ServerIP HOST to transfer it when the server is available.'
}
