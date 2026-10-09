$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat test distZip --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle packaging failed: $LASTEXITCODE" }
    $zip = Join-Path $PSScriptRoot 'build/distributions/simple-ide.zip'
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$zip.sha256", "$hash  simple-ide.zip`n", [Text.Encoding]::ASCII)
    Write-Output "Package: $zip"
} finally {
    Pop-Location
}
