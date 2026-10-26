# 外部 PL/SQL Package 資料夾載入 POC 報告

## 結果摘要

外部目錄已可透過 `LINEAGE_PACKAGES_PATH` 載入，並沿用既有 Chunking、Retrieval、SQL Dependency Analyzer、Call Graph 與問答流程。原始檔案只讀取；本次測試目錄是由專案內虛構 Package 複製而成，沒有公司程式碼，也沒有呼叫雲端模型。

| 驗收項目 | 結果 | 說明 |
|---|---|---|
| 使用環境變數切換外部目錄 | PASS | 本次以 Windows 絕對路徑啟動 Spring Boot，啟動統計顯示使用該外部根目錄。 |
| 預設模擬目錄與既有測試 | PASS | 未設定新環境變數時保留 `../packages` 預設值；既有 Maven 測試全數通過。 |
| 遞迴掃描、相對路徑與原始行號 | PASS | Retrieval 回傳 `nested/...` 路徑，來源行號仍與原始檔案一致。 |
| Retrieval 與 SQL Dependency Analyzer | PASS | `TGT_POLICY.EFFECTIVE_DATE` 找到 Load、Transform、Extract 三個 Package 的證據。 |
| Procedure Call Graph | PASS | 外部根目錄查詢 `PKG_POLICY_LOAD.RUN_FULL_LOAD` 回傳兩條直接呼叫邊；檔案欄位保留外部相對路徑。 |
| Ollama 使用檢索證據 | PARTIAL | 本機 `qwen3:8b` 問答成功，引用了外部證據；但回答的完整血緣與呼叫順序仍有錯誤，不能視為分析正確。 |
| 無效路徑明確失敗 | PASS | 單元測試確認不存在的路徑會丟出含指定根目錄的明確錯誤，不會回退到模擬資料。 |
| 單一檔案處理失敗時繼續掃描 | PASS（受控模擬） | 測試以單檔大小上限模擬無法載入的檔案，其餘可讀檔案仍成功建立 Chunk。未以作業系統 ACL 實測真實拒讀。 |

## 實作內容

- `PlsqlChunkingService` 讀取 `lineage.packages-directory`，以啟動時單次掃描建立不可變 Chunk 快照，Retrieval 與 Call Graph 重用該快照，不在每次問答時重掃或重切。
- 掃描支援 `.pck`、`.pkb`、`.pks`、`.sql`，副檔名不分大小寫；遞迴掃描結果的 `fileName` 改為相對根目錄路徑，使用 `/` 分隔，Chunk ID 仍由相對路徑及原始內容穩定產生。
- `.pkb`、`.pck` 視為 Package Body；`.sql` 依內容中的 `PACKAGE BODY` 判定 Body。Call Graph Analyzer 也納入 `.pck` 與 `.sql` Body Chunk。
- 啟動時只輸出根目錄、檔案/Chunk 統計、耗時與最多五筆錯誤摘要，不記錄程式碼內容。
- 不跟隨目錄連結；拒絕符號連結檔案，並檢查路徑留在指定根目錄內。
- 設定了有限掃描上限：最多 10,000 個檔案、單檔 16 MiB、總讀取大小 256 MiB。可由環境變數調整，超限檔案略過並列入錯誤統計。
- 指定的根目錄不存在、不是目錄或不可讀時，Spring Bean 初始化失敗，應用不會靜默切回預設資料夾。

## 設定方式

`application.yml` 的目錄設定優先順序如下：

1. `LINEAGE_PACKAGES_PATH`
2. 舊設定 `LINEAGE_PACKAGES_DIR`
3. 未設定環境變數時，依目前工作目錄尋找 `packages` 或 `../packages`，可由 workspace 根目錄或 `backend` 目錄啟動。

PowerShell 範例：

```powershell
cd C:\path\to\ai-data-lineage\backend
$env:LINEAGE_PACKAGES_PATH = 'D:\Company\PACKAGES'
$env:LINEAGE_ASSISTANT_PROVIDER = 'ollama'
$env:OLLAMA_CHAT_MODEL = 'qwen3:8b'
mvn spring-boot:run
```

回到預設模擬資料時，在同一個 PowerShell 工作階段執行：

```powershell
Remove-Item Env:LINEAGE_PACKAGES_PATH -ErrorAction SilentlyContinue
Remove-Item Env:LINEAGE_PACKAGES_DIR -ErrorAction SilentlyContinue
```

本次沒有更改 Provider 的選擇或憑證機制。只有設定 `LINEAGE_ASSISTANT_PROVIDER=ollama` 才會選擇 Ollama。

掃描上限可選擇調整：

```powershell
$env:LINEAGE_PACKAGES_MAX_FILES = '10000'
$env:LINEAGE_PACKAGES_MAX_FILE_BYTES = '16777216'
$env:LINEAGE_PACKAGES_MAX_TOTAL_BYTES = '268435456'
```

## 測試資料與執行環境

- Java：21.0.12.1
- Spring Boot：3.5.16
- Ollama：本機 `qwen3:8b`（僅測試問答時使用）
- Fixture：從現有三個虛構 Package 的 `.pkb/.pks` 複製六個檔案到 `backend/target` 下的多層子目錄；沒有複製或修改 `packages/` 來源檔案。
- 啟動時指定 `LINEAGE_PACKAGES_PATH` 為該 Fixture，使用獨立連接埠 `18187`；問答完成後已停止本次啟動的 Java 程序。

實際啟動日誌統計：

| 統計 | 數值 |
|---|---:|
| Package 根目錄 | `backend/target/external-packages-poc-a59b33a56886471699d59a5ee35ab7ac`（本機暫存 Fixture） |
| 支援副檔名檔案 | 6 |
| 成功讀取 | 6 |
| 建立 Chunk | 27 |
| 讀取/解析錯誤 | 0 |
| 掃描及 Chunk 建立耗時 | 22 ms |

## Retrieval、SQL Analyzer 與 Call Graph 驗證

呼叫 `GET /api/retrieval/search?query=TGT_POLICY.EFFECTIVE_DATE&limit=8`，得到 3 個 Chunk：

| Package / Procedure | 外部相對路徑 | 範圍 |
|---|---|---:|
| `PKG_POLICY_LOAD.LOAD_TARGET` | `nested/load/PKG_POLICY_LOAD.pkb` | 10–40 |
| `PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS` | `nested/transform/PKG_POLICY_TRANSFORM.pkb` | 10–36 |
| `PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE` | `nested/extract/PKG_POLICY_EXTRACT.pkb` | 8–33 |

這三段證據包含 Load 的目標欄位 MERGE、Transform 的欄位 CASE/NVL 計算，以及 Extract 的 `POLICY_START_DT` 至中介欄位映射。SQL Dependency Analyzer 在這些 Chunk 中建立的欄位依賴供 Retrieval 使用，沒有讀取 `evaluation/` 的標準答案。

呼叫 `GET /api/call-graph?package=PKG_POLICY_LOAD&procedure=RUN_FULL_LOAD`，取得 2 條由 `RUN_FULL_LOAD` 發出的直接呼叫邊；兩條邊都位於 `nested/load/PKG_POLICY_LOAD.pkb`。這是依 caller 篩選後的直接邊數，不代表整張 Call Graph 只有兩條邊。

## Ollama 問答驗證

使用正式 `POST /api/assistant/ask`，request 只帶問題及明確欄位 `TGT_POLICY.EFFECTIVE_DATE`。Retrieval 回傳上述 3 個 Chunk；API 回應 Sources 的 `fileName`、Procedure 與原始行號均為外部相對路徑。Ollama 回答提到 `[S1]`、`[S2]`、`[S3]` 及 Call Graph 證據，證明外部載入結果進入正式 Retrieval 與 Prompt 流程。

回答品質有明顯缺口：模型將中介 `STG_POLICY.*` 欄位稱為原始來源，沒有追到 `SRC_POLICY.*`，且總結的呼叫鏈方向錯誤。這次 POC 驗證的是外部目錄能接入現有問答證據流，不代表 LLM 的血緣敘述通過正確性驗收。現有 Prompt 的追蹤深度與模型遵循能力仍需獨立改善及評測。

## 自動化測試結果

在 `backend` 執行 `mvn test`：**53 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。

新增外部載入測試涵蓋：遞迴巢狀目錄、六個模擬 Package 檔案、相對路徑與原始行號、四種大小寫不敏感副檔名、Retrieval 三階段證據、Call Graph、工作目錄預設路徑解析、錯誤根目錄，以及超限單檔不影響其他檔案。既有 Retrieval Evaluation 亦通過（8 題計分、Macro/Micro package file recall 100%、Archive false positive 0）。

## 格式支援與風險

- 掃描層支援上述四種副檔名，但現有 Chunking 與 SQL Analyzer 是規則式解析，並非完整 Oracle PL/SQL Parser。未支援或格式不符的程式單元可能被標為 `UNKNOWN`，部分 SQL 語法、動態 SQL、跨檔多單元及複雜巢狀結構仍可能無法完整解析。
- Call Graph 目前只分析能由現有規則辨識的直接呼叫語句；動態/間接呼叫、Overload 解析及非標準格式仍不保證。
- 本次使用六個小型模擬檔案，不是數百至數千支正式 PCK 的效能基準。大型目錄還需量測啟動延遲、記憶體、解析覆蓋率與重啟更新成本；目前快照只在啟動時建立，修改來源檔後需重啟應用才會重新載入。
- 不可讀檔的錯誤捕捉採逐檔隔離；本機測試以檔案大小上限驗證局部略過，尚未以正式 Windows ACL 建立 OS 層級的拒讀測試。
- 不會自動上傳原始碼；雲端 Provider 的既有設定仍可用，若選用雲端模型，檢索到的程式碼是否傳出本機取決於原有 Provider 選擇。正式公司 PCK 應只使用公司核准的模型與網路設定。
