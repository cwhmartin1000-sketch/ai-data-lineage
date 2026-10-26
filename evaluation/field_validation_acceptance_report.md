# PCK 欄位用途與檢核條件驗收報告

## 結論

- 三個目標欄位的 9 個驗收項目：**PASS 0、PARTIAL 0、FAIL 9**。端對端回答正確率 **0/9（0%）**。
- 主要失敗在 **Java SQL Dependency Analyzer / Retrieval**：三個限定欄位的正式 Retrieval 都回傳空陣列，9 次 Assistant API 回應都沒有 Sources 或 Procedure Calls。Ollama 因此回答證據不足，沒有捏造欄位來源、規則或行號。
- PCK 掃描、Chunking 與 Call Graph 有部分功能通過：4 個 `.pck` 全數讀取，建立 20 個 Chunk；Call Graph 找到 7 條符合原始碼的呼叫邊。這些結果不能抵銷欄位 Retrieval 的失敗。
- **不建議以目前版本向主管展示「可以回答欄位用途、檢核條件及精確行號」的能力。**可展示 PCK 掃描／Call Graph，並明確示範 Retrieval 目前會安全地回答證據不足。

## 測試資料與環境

- 新增 fixture：`evaluation/field_validation_fixture/`，僅有 4 支全新虛構 `.pck` 原始碼。未複製既有 `PKG_POLICY_*` Package。
- Package：`PKG_CONTRACT_INTAKE.pck`、`PKG_CONTRACT_SCREEN.pck`、`PKG_CONTRACT_RULES.pck`、`PKG_CONTRACT_PUBLISH.pck`。包含跨 Package 呼叫、中介表、LEFT JOIN、NVL、IF/ELSIF/ELSE、NULL／狀態／日期檢核、CASE WHEN、日期轉換及 `ARCHIVE_BEGIN_ON` 相似名稱干擾欄位。
- 本次三欄：`TGT_AGREEMENT.CONTRACT_KEY`（簡單）、`TGT_AGREEMENT.PARTY_LABEL`（中等）、`TGT_AGREEMENT.COVERAGE_BEGIN_DATE`（複雜）。
- 後端：Spring Boot 3.5.16、Java 21.0.12.1；啟動時 `LINEAGE_PACKAGES_PATH` 指向 fixture。為使用目前編譯輸出，執行 `target/classes` 搭配既有 JAR 內 112 個依賴 JAR；舊可執行 JAR 不含本次 `.pck` 掃描程式碼。
- Provider API 回報 `ollama / qwen3:8b / ready=true`；本機 Ollama `qwen3:8b`。9 次獨立 Assistant API 呼叫全部使用本機模型，沒有呼叫雲端 LLM。
- 啟動掃描統計：根目錄為 fixture 絕對路徑；4 檔、4 檔讀取成功、20 Chunks、0 failed、15 ms。其中 8 個標記為具名 Procedure、12 個為 `UNKNOWN` 前後區塊；四個 package 的第一個 Routine Chunk 起始於 Specification 宣告行（分別為第 2 行），而非 Body 實作行（第 7 行）。Procedure 名稱仍可辨識，但這種合併格式會讓 Chunk 範圍帶入宣告區，需列為 `.pck` Chunking 限制。
- 固定標準答案先於 Assistant 測試建立。`evaluation/validate_field_validation_expected.ps1` 驗證只有 `.pck` 的 fixture、所有 Procedure 範圍及 **54 個行號／程式碼引用**均與原始檔完全一致，結果 PASS。標準答案人工逐行整理，未讀取 Assistant 輸出。
- 9 次 API 請求只包含 `question` 與對應的 `column`；沒有送出標準答案或 Evaluation 欄位。實際 request/response 存於 `backend/target/field-validation-api-transcripts.json`，下文逐題摘要及原始回答。

## 固定人工標準答案

### TGT_AGREEMENT.CONTRACT_KEY（simple）

**用途：** 儲存被發佈的契約識別碼；LOAD_SOURCE_ROWS 從 RAW_AGREEMENT 複製至 STG_AGREEMENT，BUILD_RESULT_ROWS 再複製至 STG_AGREEMENT_READY，MERGE_TARGET_ROWS 寫入目標表。

**原始來源：** RAW_AGREEMENT.CONTRACT_KEY

**資料流：**
- RAW_AGREEMENT.CONTRACT_KEY -> STG_AGREEMENT.CONTRACT_KEY
- STG_AGREEMENT.CONTRACT_KEY -> STG_AGREEMENT_READY.CONTRACT_KEY
- STG_AGREEMENT_READY.CONTRACT_KEY -> TGT_AGREEMENT.CONTRACT_KEY

**原始碼規則：**
- 來源擷取 SQL 未對 CONTRACT_KEY 使用轉換函數；原值依投影順序映射。
- CHECK_STAGED_ROWS 會檢查 CONTRACT_KEY IS NULL 並設定 CHECK_RESULT=REJECT_KEY；此檢核不改寫 CONTRACT_KEY 本身。
- 資料是否被排除於最終載入：本 fixture 中未看到依 CONTRACT_KEY 檢核結果過濾或排除契約的 SQL，不能推斷有排除。

**行號證據（fixture 原始檔 1-based 行號）：**
- `PKG_CONTRACT_INTAKE.pck` / `LOAD_SOURCE_ROWS`（Procedure 範圍 12-34；支持：來源欄位到第一個中介欄位的直接映射；證據行：14, 15, 24, 32）
  - L14: `INSERT INTO STG_AGREEMENT (`
  - L15: `CONTRACT_KEY,`
  - L24: `SELECT a.CONTRACT_KEY,`
  - L32: `FROM RAW_AGREEMENT a`
- `PKG_CONTRACT_RULES.pck` / `BUILD_RESULT_ROWS`（Procedure 範圍 14-38；支持：中介欄位至轉換結果欄位映射；證據行：16, 17, 21, 37）
  - L16: `INSERT INTO STG_AGREEMENT_READY (`
  - L17: `CONTRACT_KEY,`
  - L21: `SELECT s.CONTRACT_KEY,`
  - L37: `FROM STG_AGREEMENT s;`
- `PKG_CONTRACT_PUBLISH.pck` / `MERGE_TARGET_ROWS`（Procedure 範圍 13-33；支持：結果中介欄位到目標欄位的 MERGE 映射；證據行：15, 17, 23, 25, 29）
  - L15: `MERGE INTO TGT_AGREEMENT t`
  - L17: `SELECT CONTRACT_KEY, PARTY_LABEL, CALCULATED_BEGIN_ON`
  - L23: `t.COVERAGE_BEGIN_DATE = s.CALCULATED_BEGIN_ON`
  - L25: `CONTRACT_KEY,`
  - L29: `s.CONTRACT_KEY,`

### TGT_AGREEMENT.PARTY_LABEL（medium）

**用途：** 儲存用於目標契約資料的 Party 顯示名稱；Extract 從 RAW_PARTY 透過 PARTY_KEY JOIN 取得 PARTY_NAME，缺值以 UNKNOWN PARTY 取代；轉換時再以 UNNAMED PARTY 作第二層 NVL 預設。

**原始來源：** RAW_PARTY.PARTY_NAME, RAW_AGREEMENT.PARTY_KEY, RAW_PARTY.PARTY_KEY

**資料流：**
- RAW_PARTY.PARTY_NAME -> STG_AGREEMENT.PARTY_LABEL via NVL(..., UNKNOWN PARTY) and LEFT JOIN
- STG_AGREEMENT.PARTY_LABEL -> STG_AGREEMENT_READY.PARTY_LABEL via NVL(..., UNNAMED PARTY)
- STG_AGREEMENT_READY.PARTY_LABEL -> TGT_AGREEMENT.PARTY_LABEL

**原始碼規則：**
- Extract 以 LEFT JOIN RAW_PARTY p ON p.PARTY_KEY = a.PARTY_KEY。
- 若 PARTY_NAME 為 NULL，Extract 的 NVL 預設為 UNKNOWN PARTY。
- 轉換階段若中介 PARTY_LABEL 為 NULL，NVL 預設為 UNNAMED PARTY。
- 沒有對 Party 名稱做 IF/CASE 驗證或排除。

**行號證據（fixture 原始檔 1-based 行號）：**
- `PKG_CONTRACT_INTAKE.pck` / `LOAD_SOURCE_ROWS`（Procedure 範圍 12-34；支持：LEFT JOIN 以及第一層 NVL；證據行：17, 26, 32, 33）
  - L17: `PARTY_LABEL,`
  - L26: `NVL(p.PARTY_NAME, 'UNKNOWN PARTY'),`
  - L32: `FROM RAW_AGREEMENT a`
  - L33: `LEFT JOIN RAW_PARTY p ON p.PARTY_KEY = a.PARTY_KEY;`
- `PKG_CONTRACT_RULES.pck` / `BUILD_RESULT_ROWS`（Procedure 範圍 14-38；支持：第二層 NVL 與結果欄位投影；證據行：18, 22）
  - L18: `PARTY_LABEL,`
  - L22: `NVL(s.PARTY_LABEL, 'UNNAMED PARTY'),`
- `PKG_CONTRACT_PUBLISH.pck` / `MERGE_TARGET_ROWS`（Procedure 範圍 13-33；支持：中介 Party label 寫入目標欄位；證據行：22, 26, 30）
  - L22: `t.PARTY_LABEL = s.PARTY_LABEL,`
  - L26: `PARTY_LABEL,`
  - L30: `s.PARTY_LABEL,`

### TGT_AGREEMENT.COVERAGE_BEGIN_DATE（complex）

**用途：** 目標契約資料的 Coverage Begin 日期欄位。RAW_AGREEMENT 的來源日期、申請日期及狀態先進入 STG_AGREEMENT；CHECK_STAGED_ROWS 依 IF/ELSIF 條件更新 CHECK_RESULT；BUILD_RESULT_ROWS 再按狀態與檢核結果以 CASE 產生 CALCULATED_BEGIN_ON；MERGE_TARGET_ROWS 寫入目標。

**原始來源：** RAW_AGREEMENT.COVERAGE_BEGIN_ON, RAW_AGREEMENT.REQUESTED_BEGIN_ON, RAW_AGREEMENT.CONTRACT_STATE
**條件來源：** STG_AGREEMENT.CHECK_RESULT，由 PKG_CONTRACT_SCREEN.CHECK_STAGED_ROWS 依 CONTRACT_KEY、SOURCE_BEGIN_ON、CONTRACT_STATE 及日期是否晚於 TRUNC(SYSDATE) 的 IF/ELSIF/ELSE 檢核設定

**資料流：**
- RAW_AGREEMENT.COVERAGE_BEGIN_ON -> STG_AGREEMENT.SOURCE_BEGIN_ON
- RAW_AGREEMENT.REQUESTED_BEGIN_ON -> STG_AGREEMENT.REQUESTED_BEGIN_ON
- RAW_AGREEMENT.CONTRACT_STATE -> STG_AGREEMENT.CONTRACT_STATE
- STG_AGREEMENT.CHECK_RESULT / CONTRACT_STATE / SOURCE_BEGIN_ON / REQUESTED_BEGIN_ON -> STG_AGREEMENT_READY.CALCULATED_BEGIN_ON via CASE and date conversion
- STG_AGREEMENT_READY.CALCULATED_BEGIN_ON -> TGT_AGREEMENT.COVERAGE_BEGIN_DATE via MERGE

**原始碼規則：**
- IF CONTRACT_KEY IS NULL：CHECK_RESULT 保留既有非 NULL 值，否則設為 REJECT_KEY。
- ELSIF SOURCE_BEGIN_ON IS NULL：CHECK_RESULT 預設為 REJECT_BEGIN_DATE。
- ELSIF CONTRACT_STATE NOT IN (ACTIVE, PENDING)：CHECK_RESULT 預設為 REVIEW_STATE。
- ELSIF SOURCE_BEGIN_ON > TRUNC(SYSDATE)：CHECK_RESULT 預設為 FUTURE_BEGIN。
- ELSE：CHECK_RESULT 預設為 READY。
- Transform CASE：ACTIVE 且 READY 使用 SOURCE_BEGIN_ON；PENDING 且 READY 使用 NVL(REQUESTED_BEGIN_ON, SOURCE_BEGIN_ON + 15)；CHECK_RESULT 非 READY 時結果為 NULL；其餘分支使用 SOURCE_BEGIN_ON。所有日期分支經 TO_CHAR/TO_DATE 格式化。
- PCK 中未見以 CHECK_RESULT 明確 WHERE 排除資料；CASE 對非 READY 設為 NULL，不能稱為刪除/排除資料。

**行號證據（fixture 原始檔 1-based 行號）：**
- `PKG_CONTRACT_INTAKE.pck` / `LOAD_SOURCE_ROWS`（Procedure 範圍 12-34；支持：來源日期與狀態映射至中介資料；證據行：18, 19, 20, 27, 28, 29）
  - L18: `CONTRACT_STATE,`
  - L19: `SOURCE_BEGIN_ON,`
  - L20: `REQUESTED_BEGIN_ON,`
  - L27: `a.CONTRACT_STATE,`
  - L28: `a.COVERAGE_BEGIN_ON,`
  - L29: `a.REQUESTED_BEGIN_ON,`
- `PKG_CONTRACT_SCREEN.pck` / `CHECK_STAGED_ROWS`（Procedure 範圍 12-40；支持：狀態、NULL、未來日期檢核與 CHECK_RESULT 預設值；證據行：18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38）
  - L18: `IF r.CONTRACT_KEY IS NULL THEN`
  - L20: `SET CHECK_RESULT = NVL(s.CHECK_RESULT, 'REJECT_KEY')`
  - L22: `ELSIF r.SOURCE_BEGIN_ON IS NULL THEN`
  - L24: `SET CHECK_RESULT = NVL(s.CHECK_RESULT, 'REJECT_BEGIN_DATE')`
  - L26: `ELSIF r.CONTRACT_STATE NOT IN ('ACTIVE', 'PENDING') THEN`
  - L28: `SET CHECK_RESULT = NVL(s.CHECK_RESULT, 'REVIEW_STATE')`
  - L30: `ELSIF r.SOURCE_BEGIN_ON > TRUNC(SYSDATE) THEN`
  - L32: `SET CHECK_RESULT = NVL(s.CHECK_RESULT, 'FUTURE_BEGIN')`
  - L34: `ELSE`
  - L36: `SET CHECK_RESULT = NVL(s.CHECK_RESULT, 'READY')`
  - L38: `END IF;`
- `PKG_CONTRACT_RULES.pck` / `BUILD_RESULT_ROWS`（Procedure 範圍 14-38；支持：CASE 分支、狀態/檢核依賴、NVL 回退與日期轉換；證據行：23, 24, 25, 26, 27, 28, 30, 33, 34, 35, 37）
  - L23: `CASE`
  - L24: `WHEN s.CONTRACT_STATE = 'ACTIVE'`
  - L25: `AND s.CHECK_RESULT = 'READY'`
  - L26: `THEN TO_DATE(TO_CHAR(s.SOURCE_BEGIN_ON, 'YYYY-MM-DD'), 'YYYY-MM-DD')`
  - L27: `WHEN s.CONTRACT_STATE = 'PENDING'`
  - L28: `AND s.CHECK_RESULT = 'READY'`
  - L30: `TO_CHAR(NVL(s.REQUESTED_BEGIN_ON, s.SOURCE_BEGIN_ON + 15), 'YYYY-MM-DD'),`
  - L33: `WHEN s.CHECK_RESULT <> 'READY'`
  - L34: `THEN CAST(NULL AS DATE)`
  - L35: `ELSE TO_DATE(TO_CHAR(s.SOURCE_BEGIN_ON, 'YYYY-MM-DD'), 'YYYY-MM-DD')`
  - L37: `FROM STG_AGREEMENT s;`
- `PKG_CONTRACT_PUBLISH.pck` / `MERGE_TARGET_ROWS`（Procedure 範圍 13-33；支持：計算欄位映射至最終目標欄位；證據行：15, 23, 27, 31）
  - L15: `MERGE INTO TGT_AGREEMENT t`
  - L23: `t.COVERAGE_BEGIN_DATE = s.CALCULATED_BEGIN_ON`
  - L27: `COVERAGE_BEGIN_DATE`
  - L31: `s.CALCULATED_BEGIN_ON`

## Java Analyzer / Retrieval 結果

以下使用限定名稱執行現有 `GET /api/retrieval/search?query=<TABLE.COLUMN>&limit=8`。三個目標均回傳 `[]`，因此正式欄位依賴 Retrieval 沒有證據可交給 Assistant。

| 目標欄位 | Retrieval Chunk 數 | Call Graph | 結果 |
|---|---:|---:|---|
| `TGT_AGREEMENT.CONTRACT_KEY` | 0 | 0（Ask API） | FAIL：無欄位依賴證據進入問答 |
| `TGT_AGREEMENT.PARTY_LABEL` | 0 | 0（Ask API） | FAIL：無欄位依賴證據進入問答 |
| `TGT_AGREEMENT.COVERAGE_BEGIN_DATE` | 0 | 0（Ask API） | FAIL：無欄位依賴證據進入問答 |

另做不作為評分輸入的非限定識別字診斷：

- `CONTRACT_KEY`：回傳 Intake、Screen、Rules 共 3 Chunks，但缺少 Publish / MERGE 的目標寫入證據；Screen 的 15 條依賴邊含有錯誤依賴：WHERE 子句中的 `r.CONTRACT_KEY` 被當成 `CHECK_RESULT` 的值來源（例如行 21、25、29、33、37）。該欄位只出現在 UPDATE 篩選條件，不是 SET 運算式來源，這是 UPDATE parser 把 WHERE 條件併入 SET 值解析的 False Positive。
- `PARTY_LABEL`：回傳 Intake、Rules 共 2 Chunks，缺少 Publish / MERGE 寫入目標的邊。
- `COVERAGE_BEGIN_DATE`：只回傳 Publish Chunk，且 `dependencyEdges=[]`；Transform CASE、Screen IF 與 Intake 原始來源都未沿資料依賴找回。
- 所以不能只看非限定名稱有搜尋到文字就判定血緣完整；它漏掉最終 MERGE 到目標欄位的關鍵邊，且有條件欄位 False Positive。

### 確定性 Procedure Call Graph

`GET /api/call-graph` 回傳 7 條邊，均能對上 PCK 直接呼叫語句；這是呼叫關係，不是欄位資料流：

- `PKG_CONTRACT_INTAKE.RUN_EXTRACT -> PKG_CONTRACT_INTAKE.LOAD_SOURCE_ROWS` — `PKG_CONTRACT_INTAKE.pck:9`：`LOAD_SOURCE_ROWS;`（crossPackage=False）
- `PKG_CONTRACT_PUBLISH.RUN_PUBLISH -> PKG_CONTRACT_RULES.RUN_TRANSFORM` — `PKG_CONTRACT_PUBLISH.pck:9`：`PKG_CONTRACT_RULES.RUN_TRANSFORM;`（crossPackage=True）
- `PKG_CONTRACT_PUBLISH.RUN_PUBLISH -> PKG_CONTRACT_PUBLISH.MERGE_TARGET_ROWS` — `PKG_CONTRACT_PUBLISH.pck:10`：`MERGE_TARGET_ROWS;`（crossPackage=False）
- `PKG_CONTRACT_RULES.RUN_TRANSFORM -> PKG_CONTRACT_INTAKE.RUN_EXTRACT` — `PKG_CONTRACT_RULES.pck:9`：`PKG_CONTRACT_INTAKE.RUN_EXTRACT;`（crossPackage=True）
- `PKG_CONTRACT_RULES.RUN_TRANSFORM -> PKG_CONTRACT_SCREEN.RUN_SCREENING` — `PKG_CONTRACT_RULES.pck:10`：`PKG_CONTRACT_SCREEN.RUN_SCREENING;`（crossPackage=True）
- `PKG_CONTRACT_RULES.RUN_TRANSFORM -> PKG_CONTRACT_RULES.BUILD_RESULT_ROWS` — `PKG_CONTRACT_RULES.pck:11`：`BUILD_RESULT_ROWS;`（crossPackage=False）
- `PKG_CONTRACT_SCREEN.RUN_SCREENING -> PKG_CONTRACT_SCREEN.CHECK_STAGED_ROWS` — `PKG_CONTRACT_SCREEN.pck:9`：`CHECK_STAGED_ROWS;`（crossPackage=False）

但 Assistant Service 只有在欄位 Retrieval 有結果時才查相關 Call Graph。本次所有 Assistant 回應都 `sources=[]`、`procedureCalls=[]`，所以獨立 API 找得到的 Call Graph 沒有進入 LLM 問答。

## Ollama 實際問答與九項驗收

每個目標各問用途、檢核條件、檔案／Procedure／行號，共 9 次獨立 `POST /api/assistant/ask`。各 request 僅含 question/column。因每題 Sources 與 Procedure Calls 都是空陣列，Ollama 均回覆證據不足。下列保存實際請求與原始回答：

### TGT_AGREEMENT.CONTRACT_KEY — 欄位用途 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.CONTRACT_KEY 的欄位用途、原始來源欄位與中介資料流為何？",
  "column": "TGT_AGREEMENT.CONTRACT_KEY"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 10830 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。需要以下資訊才能確認 TGT_AGREEMENT.CONTRACT_KEY 的欄位用途、原始來源欄位與中介資料流：
> 
> 1. **Verified Data Dependencies**：需要明確的欄位依賴關係，例如某個來源欄位如何轉換為 TGT_AGREEMENT.CONTRACT_KEY。
> 2. **Verified Procedure Calls**：需要直接呼叫的 Procedure 證據，以確認是否有相關的處理邏輯或資料轉換流程。
> 
> 若能提供上述資訊，即可進一步分析 TGT_AGREEMENT.CONTRACT_KEY 的原始來源與資料流。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.CONTRACT_KEY — 檢核條件 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.CONTRACT_KEY 有哪些 NULL、狀態或日期檢核？請說明成立、不成立與預設值處理。",
  "column": "TGT_AGREEMENT.CONTRACT_KEY"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 3278 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 要確認 TGT_AGREEMENT.CONTRACT_KEY 的 NULL、狀態或日期檢核，需要以下資訊：
> 
> 1. **欄位依賴證據**：需有明確的資料流邊（如 SOURCE.COLUMN -> TGT_AGREEMENT.CONTRACT_KEY）才能確認其來源與處理邏輯。
> 2. **條件與檢核邏輯**：需有明確的 CASE 條件、 NULL 檢核、狀態或日期檢查的 SQL 語句或 PL/SQL 代碼。
> 3. **預設值處理**：需有明確的 DEFAULT 子句或 NVL 等函數使用情況。
> 
> 目前未檢索到任何相關的資料依賴或程式碼證據，因此無法回答該問題。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.CONTRACT_KEY — PCK 檔名／Procedure／行號 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.CONTRACT_KEY 請列出能證明欄位用途、來源映射與檢核條件的 PCK 檔名、Procedure、精確行號和程式碼。",
  "column": "TGT_AGREEMENT.CONTRACT_KEY"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 3386 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 需要以下資訊才能確認 TGT_AGREEMENT.CONTRACT_KEY 的欄位用途、來源映射與檢核條件：
> 
> 1. **Verified Data Dependencies** 中的欄位依賴關係，以明確證明 TGT_AGREEMENT.CONTRACT_KEY 的來源欄位或條件。
> 2. **Verified Procedure Calls** 中的 Procedure 呼叫證據，以確認是否有直接呼叫與 TGT_AGREEMENT.CONTRACT_KEY 有關的 Procedure。
> 3. 相關的 PL/SQL Package 檔名、Procedure 名稱、精確行號與程式碼片段，以供進一步驗證。
> 
> 目前未檢索到任何程式碼證據，因此無法確認 TGT_AGREEMENT.CONTRACT_KEY 的欄位用途、來源映射與檢核條件。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.PARTY_LABEL — 欄位用途 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.PARTY_LABEL 的欄位用途、原始來源欄位與中介資料流為何？",
  "column": "TGT_AGREEMENT.PARTY_LABEL"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 1956 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 需要以下資訊才能確認 TGT_AGREEMENT.PARTY_LABEL 的欄位用途、原始來源欄位與中介資料流：
> 
> 1. **Verified Data Dependencies** 中與 TGT_AGREEMENT.PARTY_LABEL 有關的欄位依賴關係。
> 2. **Verified Procedure Calls** 中與 TGT_AGREEMENT.PARTY_LABEL 有關的直接呼叫關係。
> 
> 請提供上述資訊後，再進行進一步分析。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.PARTY_LABEL — 檢核條件 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.PARTY_LABEL 有哪些 NULL、狀態或日期檢核？請說明成立、不成立與預設值處理。",
  "column": "TGT_AGREEMENT.PARTY_LABEL"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 4889 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 要確認 TGT_AGREEMENT.PARTY_LABEL 的 NULL、狀態或日期檢核，需要以下資訊：
> 
> 1. **欄位來源**：需明確 TGT_AGREEMENT.PARTY_LABEL 的資料來源欄位（例如：SOURCE_TABLE.COLUMN_NAME），以確認其值是否來自其他表或計算邏輯。
> 2. **NULL 檢核**：需確認是否有程式碼對 TGT_AGREEMENT.PARTY_LABEL 進行 NULL 檢核（例如：IF TGT_AGREEMENT.PARTY_LABEL IS NULL THEN ... END IF;）。
> 3. **狀態檢核**：需確認是否有狀態相關的條件判斷（例如：CASE WHEN TGT_AGREEMENT.PARTY_LABEL = 'ACTIVE' THEN ... END;）。
> 4. **日期檢核**：需確認是否有日期相關的條件判斷（例如：CASE WHEN TGT_AGREEMENT.PARTY_LABEL > SYSDATE THEN ... END;）。
> 5. **預設值處理**：需確認是否有預設值設定（例如：TGT_AGREEMENT.PARTY_LABEL := 'DEFAULT';）。
> 
> 請提供上述資訊以進行進一步分析。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.PARTY_LABEL — PCK 檔名／Procedure／行號 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.PARTY_LABEL 請列出能證明欄位用途、來源映射與檢核條件的 PCK 檔名、Procedure、精確行號和程式碼。",
  "column": "TGT_AGREEMENT.PARTY_LABEL"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 2830 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。  
> 需要以下資訊才能確認 TGT_AGREEMENT.PARTY_LABEL 的欄位用途、來源映射與檢核條件：  
> 1. **Verified Data Dependencies** 中的欄位依賴關係，以確認 TGT_AGREEMENT.PARTY_LABEL 的來源欄位。  
> 2. **Verified Procedure Calls** 中的 Procedure 呼叫關係，以確認是否有與 TGT_AGREEMENT.PARTY_LABEL 相關的 Procedure 並定位其程式碼位置。  
> 
> 目前未找到任何相關的程式碼證據，因此無法提供 PCK 檔名、Procedure、精確行號和程式碼。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.COVERAGE_BEGIN_DATE — 欄位用途 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的欄位用途、原始來源欄位與中介資料流為何？",
  "column": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 2272 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 需要以下資訊才能確認 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的欄位用途、原始來源欄位與中介資料流：
> 
> 1. **Verified Data Dependencies** 中的欄位依賴關係，用以確認該欄位的來源欄位與資料流。
> 2. **Verified Procedure Calls** 中的直接呼叫關係，用以確認是否有 Procedure 處理該欄位的值。
> 
> 目前未檢索到任何程式碼證據，因此無法提供相關資訊。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.COVERAGE_BEGIN_DATE — 檢核條件 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE 有哪些 NULL、狀態或日期檢核？請說明成立、不成立與預設值處理。",
  "column": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 4057 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。
> 
> 要確認 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的 NULL、狀態或日期檢核，需要以下資訊：
> 
> 1. **資料欄位依賴**：需要明確的資料欄位依賴關係，例如哪個欄位或表的資料會影響 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的值，以及是否有條件式轉換或驗證邏輯。
> 2. **程式碼證據**：需要 PL/SQL 中直接處理 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的相關程式碼，例如賦值、條件判斷、日期格式檢查、NULL 值處理等。
> 3. **Procedure 呼叫**：如果有相關的 Procedure 呼叫，也需要確認其與 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的關聯。
> 
> 目前無任何證據可供分析，因此無法說明 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的 NULL、狀態或日期檢核規則。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

### TGT_AGREEMENT.COVERAGE_BEGIN_DATE — PCK 檔名／Procedure／行號 — **FAIL**

**實際 POST JSON：**
```json
{
  "question": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE 請列出能證明欄位用途、來源映射與檢核條件的 PCK 檔名、Procedure、精確行號和程式碼。",
  "column": "TGT_AGREEMENT.COVERAGE_BEGIN_DATE"
}
```

**Retrieval sources：** 0；**Procedure Calls：** 0；**耗時：** 3291 ms；**錯誤：** 無

**Ollama 原始回答：**
> 目前檢索證據不足。  
> 需要以下資訊才能確認 TGT_AGREEMENT.COVERAGE_BEGIN_DATE 的欄位用途、來源映射與檢核條件：
> 
> 1. **Verified Data Dependencies** 中的欄位依賴關係，以確認該欄位的直接來源或原始來源。
> 2. **Verified Procedure Calls** 中的 Procedure 呼叫證據，以確認是否有直接呼叫與該欄位相關的 Procedure。
> 3. 具體的 PL/SQL Package 檔名、Procedure 名稱、行號與程式碼片段，以直接驗證欄位用途與映射規則。
> 
> 目前未檢索到任何相關證據，無法提供 PCK 檔名、Procedure、精確行號和程式碼。

**評分原因：** 本項需要原始程式碼證據；該次 API 的 `sources` 與 `procedureCalls` 都是空陣列，模型明確回答證據不足，因此驗收 FAIL。失敗歸因於 Retrieval 未提供必要證據，不能據此判斷模型讀取證據後的推理能力。

## 九項評分彙總

| 欄位 | 欄位用途 | 檢核條件 | PCK 行號 | 主要失敗歸因 |
|---|---|---|---|---|
| `TGT_AGREEMENT.CONTRACT_KEY` | FAIL | FAIL | FAIL | Java Analyzer / Retrieval 未提供證據；Ollama 安全 abstain |
| `TGT_AGREEMENT.PARTY_LABEL` | FAIL | FAIL | FAIL | Java Analyzer / Retrieval 未提供證據；Ollama 安全 abstain |
| `TGT_AGREEMENT.COVERAGE_BEGIN_DATE` | FAIL | FAIL | FAIL | Java Analyzer / Retrieval 未提供證據；Ollama 安全 abstain |

- PASS：0/9；PARTIAL：0/9；FAIL：9/9。
- 9 次問答總耗時：30.18 秒；平均 3353.1 ms。
- 模型回答正確性：模型未就欄位內容提出可評分的事實；9/9 均明確表示證據不足。以「能否完成該項驗收」計，回答通過 0/9；未觀察到編造來源或行號。

## Maven 測試

- `mvn -o test`：**BUILD SUCCESS**，53 tests run、0 failures、0 errors、0 skipped。
- Maven 第一次在 Sandbox 內因無法開啟既有 `.m2` artifact lock 檔而停止；核准讀寫既有本機 Maven 快取後以 offline 模式重跑成功，沒有下載新模型或安裝新套件。
- `ChunkRetrievalEvaluationTest` 的既有離線評估也執行完畢；本次未修改測試題目或標準答案。

## 主要問題與 Demo 建議

1. **MERGE inline view 欄位投影未建立欄位邊。** Fixture 使用 `USING (SELECT CONTRACT_KEY, ... FROM STG_AGREEMENT_READY) s`；Analyzer 未把 inline-view 裡未限定的投影欄位解析成 `s.<column>` 到中介表欄位的映射，三個 TGT 限定欄位因此找不到 Load 寫入邊。
2. **UPDATE 解析把 WHERE 條件當成 SET 值依賴。** 它把 CONTRACT_KEY 篩選條件錯誤掛到 CHECK_RESULT 值依賴上，可能產生錯誤血緣。
3. **Call Graph 未能補足空 Retrieval。** Call Graph 獨立找到 7 條正確呼叫邊，但 Ask Service 在 Retrieval 為空時不查 Call Graph，無法提供檢核 Procedure 原始碼給模型。

**主管 Demo：**不建議展示為已具備可靠欄位分析能力。若展示，應限定為 PCK 掃描與 Call Graph 的技術驗證，清楚註明欄位 Retrieval 未通過；不要展示成可追出完整業務血緣或檢核規則。

**附件：** `evaluation/field_validation_expected.json`（固定標準答案）；`evaluation/validate_field_validation_expected.ps1`（54 個程式碼／行號引用驗證器）；`backend/target/field-validation-api-transcripts.json`（9 次原始 API request/response）。fixture 目錄只含四支 `.pck`。


