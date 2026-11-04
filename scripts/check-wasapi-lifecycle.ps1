param([string]$Compiler = 'gcc')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$source = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'desktop/src/main/native/audio_engine.c'))
$template = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'desktop/src/test/native/WasapiCaptureLifecycleCheck.c'))
$functions = foreach ($name in @('mute_render_endpoint', 'restore_render_endpoint_mute', 'wasapi_start', 'wasapi_read', 'wasapi_stop')) {
    $declaration = [regex]::Match($source, "(?m)^static (?:void|int|jint|jboolean) $name\([^;{}]*\)\s*\{")
    if (-not $declaration.Success) { throw "Missing production function: $name" }
    $depth = 1
    $position = $declaration.Index + $declaration.Length
    while ($depth -gt 0 -and $position -lt $source.Length) {
        if ($source[$position] -eq '{') { $depth++ }
        if ($source[$position] -eq '}') { $depth-- }
        $position++
    }
    if ($depth -ne 0) { throw "Unbalanced production function: $name" }
    $source.Substring($declaration.Index, $position - $declaration.Index)
}
$outputDir = Join-Path $repoRoot 'logs/native-lifecycle-check'
[System.IO.Directory]::CreateDirectory($outputDir) | Out-Null
$translationUnit = Join-Path $outputDir 'wasapi-lifecycle-under-test.c'
$program = Join-Path $outputDir 'wasapi-lifecycle-check.exe'
[System.IO.File]::WriteAllText($translationUnit, $template.Replace('/* PRODUCTION_FUNCTIONS */', ($functions -join "`n")), [System.Text.UTF8Encoding]::new($false))
$previousTmp = $env:TMP
$previousTemp = $env:TEMP
try {
    $env:TMP = $outputDir
    $env:TEMP = $outputDir
    & $Compiler -std=c11 -Wall -Wextra -Wno-unused-parameter $translationUnit -o $program
    if ($LASTEXITCODE -ne 0) { throw "Synthetic WASAPI lifecycle compile failed: $LASTEXITCODE" }
    & $program
    if ($LASTEXITCODE -ne 0) { throw "Synthetic WASAPI lifecycle checks failed: $LASTEXITCODE" }
} finally {
    $env:TMP = $previousTmp
    $env:TEMP = $previousTemp
}
