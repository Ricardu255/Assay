param(
    [string]$Output = "dist",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$jar = Join-Path $root "app/target/assay.jar"

if (-not $SkipBuild -and -not (Test-Path $jar)) {
    mvn -q -DskipTests -pl :assay -am clean package
}
if (-not (Test-Path $jar)) {
    throw "missing app/target/assay.jar; build it first or omit -SkipBuild"
}

$out = Join-Path $root $Output
$stage = Join-Path $out "assay"
if (Test-Path $stage) {
    Remove-Item $stage -Recurse -Force
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item $jar (Join-Path $stage "assay.jar")
Copy-Item (Join-Path $root "assay.cmd") $stage
Copy-Item (Join-Path $root "assay") $stage
Copy-Item (Join-Path $root "QUICKSTART.md") $stage
Copy-Item (Join-Path $root "examples") $stage -Recurse

$zip = Join-Path $out "assay.zip"
if (Test-Path $zip) {
    Remove-Item $zip -Force
}
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
Write-Host "created $zip"
Write-Host "users need Java 17 only; no Maven required"
