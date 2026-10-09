param(
    [string]$Prefix = (Join-Path $env:LOCALAPPDATA 'simple-ide'),
    [switch]$NoSkills
)
$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot
if (!(Test-Path (Join-Path $source 'bin/simple-ide.bat'))) {
    $source = Join-Path $source 'build/install/simple-ide'
}
if (!(Test-Path (Join-Path $source 'bin/simple-ide.bat'))) {
    throw 'Extract simple-ide.zip and run its install.ps1.'
}
$target = [IO.Path]::GetFullPath($Prefix)
$source = [IO.Path]::GetFullPath($source)
if ($target -eq $source) { throw 'Installation directory must differ from the extracted package.' }
# Preserve unrelated files; replace only files included in the distribution.
New-Item -ItemType Directory -Force -Path $target | Out-Null
foreach ($name in @('bin', 'lib')) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination $target -Recurse -Force
}
if (!$NoSkills) {
    $skill = Join-Path $PSScriptRoot 'skills/simple-ide'
    if (Test-Path $skill) {
        $skills = Join-Path $HOME '.agents/skills'
        New-Item -ItemType Directory -Force -Path $skills | Out-Null
        Copy-Item -LiteralPath $skill -Destination $skills -Recurse -Force
    }
}
$bin = Join-Path $target 'bin'
$env:PATH = "$bin;$env:PATH"
Write-Output "Installed: $bin/simple-ide.bat"
Write-Output "PATH updated for this session. Add $bin to your user PATH for future sessions."
