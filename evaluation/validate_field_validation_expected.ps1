$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $PSScriptRoot 'field_validation_fixture'
$expectedPath = Join-Path $PSScriptRoot 'field_validation_expected.json'
$expected = Get-Content -Raw $expectedPath | ConvertFrom-Json
$files = @(Get-ChildItem -File $fixture)
if ($files.Count -lt 3 -or ($files | Where-Object { $_.Extension -ine '.pck' }).Count -gt 0) {
    throw 'Fixture must contain at least three files and only .pck source files.'
}
$errors = [System.Collections.Generic.List[string]]::new()
foreach ($field in $expected.targetFields) {
    foreach ($item in $field.evidence) {
        $path = Join-Path $fixture $item.fileName
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $errors.Add("Missing fixture file: $($item.fileName)")
            continue
        }
        $lines = [System.IO.File]::ReadAllLines($path)
        if ($item.procedureStartLine -lt 1 -or $item.procedureEndLine -gt $lines.Length) {
            $errors.Add("Out-of-range procedure span: $($item.fileName) $($item.procedureName) $($item.procedureStartLine)-$($item.procedureEndLine)")
        } else {
            $span = $lines[($item.procedureStartLine - 1)..($item.procedureEndLine - 1)] -join "`n"
            if ($span -notmatch "(?im)^\s*PROCEDURE\s+$([regex]::Escape($item.procedureName))\b") {
                $errors.Add("Procedure declaration not found in span: $($item.fileName) $($item.procedureName)")
            }
        }
        foreach ($ref in $item.codeLines) {
            if ($ref.lineNumber -lt 1 -or $ref.lineNumber -gt $lines.Length) {
                $errors.Add("Out-of-range evidence line: $($item.fileName):$($ref.lineNumber)")
            } elseif ($lines[$ref.lineNumber - 1] -cne $ref.code) {
                $errors.Add("Code mismatch: $($item.fileName):$($ref.lineNumber); expected=[$($ref.code)]; actual=[$($lines[$ref.lineNumber - 1])]" )
            }
        }
    }
}
if ($errors.Count) { $errors | ForEach-Object { Write-Error $_ }; exit 1 }
[pscustomobject]@{
    status = 'PASS'
    fixtureFileCount = $files.Count
    targetFieldCount = $expected.targetFields.Count
    evidenceReferenceCount = (($expected.targetFields | ForEach-Object { $_.evidence } | ForEach-Object { $_.codeLines }).Count)
    message = 'All fixture files, procedure spans, line numbers, and evidence snippets match the source files.'
} | ConvertTo-Json -Compress
