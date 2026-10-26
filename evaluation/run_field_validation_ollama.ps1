$ErrorActionPreference = 'Stop'
$apiBase = 'http://localhost:8080'
$provider = Invoke-RestMethod -Uri "$apiBase/api/assistant/provider" -TimeoutSec 10
if ($provider.provider -ne 'ollama' -or $provider.model -ne 'qwen3:8b' -or -not $provider.ready) {
    throw "Expected ready local Ollama qwen3:8b; received provider=$($provider.provider), model=$($provider.model), ready=$($provider.ready). No evaluation requests were sent."
}

# Deliberately no reference to expected answers, related_columns, or lineage_ground_truth.
$targets = @(
    @{ id = 'A'; column = 'TGT_AGREEMENT.CONTRACT_KEY' },
    @{ id = 'B'; column = 'TGT_AGREEMENT.PARTY_LABEL' },
    @{ id = 'C'; column = 'TGT_AGREEMENT.COVERAGE_BEGIN_DATE' }
)
$prompts = @(
    @{ kind = 'usage'; suffix = '的欄位用途、原始來源欄位與中介資料流為何？' },
    @{ kind = 'validation'; suffix = '有哪些 NULL、狀態或日期檢核？請說明成立、不成立與預設值處理。' },
    @{ kind = 'source_lines'; suffix = '請列出能證明欄位用途、來源映射與檢核條件的 PCK 檔名、Procedure、精確行號和程式碼。' }
)
$results = [System.Collections.Generic.List[object]]::new()
$number = 0
foreach ($target in $targets) {
    foreach ($prompt in $prompts) {
        $number++
        $request = [ordered]@{
            question = "$($target.column) $($prompt.suffix)"
            column = $target.column
        }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-RestMethod -Method Post -Uri "$apiBase/api/assistant/ask" `
                -ContentType 'application/json' -Body ($request | ConvertTo-Json -Compress) -TimeoutSec 180
            $watch.Stop()
            $results.Add([ordered]@{
                caseId = "$($target.id)$($prompt.kind)"
                targetId = $target.id
                targetColumn = $target.column
                kind = $prompt.kind
                request = $request
                response = $response
                elapsedMs = $watch.ElapsedMilliseconds
                error = $null
            })
            Write-Output "ASK $number $($target.column) $($prompt.kind): $($watch.ElapsedMilliseconds)ms; sources=$(@($response.sources).Count); calls=$(@($response.procedureCalls).Count)"
        } catch {
            $watch.Stop()
            $results.Add([ordered]@{
                caseId = "$($target.id)$($prompt.kind)"
                targetId = $target.id
                targetColumn = $target.column
                kind = $prompt.kind
                request = $request
                response = $null
                elapsedMs = $watch.ElapsedMilliseconds
                error = $_.Exception.Message
            })
            Write-Output "ASK $number ERROR $($_.Exception.Message)"
        }
    }
}
$output = [ordered]@{
    provider = $provider.provider
    model = $provider.model
    api = "$apiBase/api/assistant/ask"
    requests = $results
}
$output | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 (Join-Path $PSScriptRoot '..\backend\target\field-validation-api-transcripts.json')
Write-Output 'TRANSCRIPTS=backend/target/field-validation-api-transcripts.json'
