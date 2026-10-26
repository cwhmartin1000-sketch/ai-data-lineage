# TGT_POLICY.EFFECTIVE_DATE 欄位分析與程式碼證據：微型 POC 驗收

## 執行摘要

| 驗收項目 | 結果 | 原因 |
|---|---|---|
| 欄位用途與程式關聯 | PARTIAL | Retrieval 找到 3 個資料處理 Procedure，足以呈現欄位寫入、計算與來源投影。但問答把資料流向概括成 Procedure 呼叫順序；實際呼叫入口的程式碼不在這次檢索的 3 個 Chunk 中。 |
| 資料轉換與業務規則 | PASS | 回答正確說明 ACTIVE、PENDING、其他狀態分支，NVL fallback 與日期格式轉換，並連接到來源及目標中繼欄位。 |
| 程式碼證據與可追溯性 | PARTIAL | API sources 提供真實檔案、Procedure、行範圍及 matched lines；回答引用 [S1]～[S3]，也列出檔名與行範圍。但正文未逐項列出實際原始行號，且有一項未被程式碼支持的日期轉換解釋。 |

## 1. 資料來源與執行環境

- 日期：2026-10-23（Asia/Taipei）。
- 使用既有 Spring Boot：`http://localhost:8080`；Provider 狀態回報 `ollama`、模型 `qwen3:8b`、`ready: true`。
- 本機 Ollama：`http://localhost:11434`，可用模型清單包含 `qwen3:8b`。
- Retrieval：`GET /api/retrieval/search?query=TGT_POLICY.EFFECTIVE_DATE&limit=8`。
- 問答：對 `POST /api/assistant/ask` 發出一筆獨立請求，輸入只有本報告列出的 question 及 `column: TGT_POLICY.EFFECTIVE_DATE`。沒有送出 Evaluation 題目、標準答案或 ground truth。
- Ollama 回應耗時：26,686 ms。未重啟或停止既有服務。
- 取回 3 個 Chunk：`PKG_POLICY_LOAD.pkb / LOAD_TARGET`、`PKG_POLICY_TRANSFORM.pkb / BUILD_TRANSFORMED_ROWS`、`PKG_POLICY_EXTRACT.pkb / LOAD_SOURCE_TO_STAGE`；共 13 條 dependencyEdges。

實際傳入的問題：

> 請針對 TGT_POLICY.EFFECTIVE_DATE 做欄位分析，說明程式使用位置、各 Package/Procedure 的用途、來源欄位及逐階段轉換、CASE WHEN/NVL/日期轉換等可由證據確認的規則，並區分資料流向與 Procedure 呼叫關係。對每項重要結論附實際檔名、Procedure、原始行號和對應程式碼；不要推測，證據不足請明確標示。

## 2. Retrieval 與 Dependency Analyzer 實際證據

| Chunk | 實際檔案／Procedure | 行範圍 | 關鍵 dependencyEdges（來源 → 目標） | 原始碼核對 |
|---|---|---:|---|---|
| S1 | `PKG_POLICY_LOAD.pkb` / `LOAD_TARGET` | 10–40 | `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` → `TGT_POLICY.EFFECTIVE_DATE`（edge evidenceLine 23）；另有 POLICY_STATUS edge | MERGE 的來源查詢取 `STG_POLICY_TRANSFORMED`（14–19）；更新映射在 23；插入目標欄在 27–39，值映射在 35。 |
| S2 | `PKG_POLICY_TRANSFORM.pkb` / `BUILD_TRANSFORMED_ROWS` | 10–36 | `STG_POLICY.START_DATE`、`REQUESTED_START_DATE`、`POLICY_STATUS` → `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` | INSERT 目標欄為 14–19；CASE 位於 22–31；來源表 `STG_POLICY` 在 35。 |
| S3 | `PKG_POLICY_EXTRACT.pkb` / `LOAD_SOURCE_TO_STAGE` | 8–33 | `SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE`（edge evidenceLine 21）；`SRC_POLICY.REQUESTED_EFFECTIVE_DT` → `STG_POLICY.REQUESTED_START_DATE`（23、24）；status 映射 edge 27 | 目標欄在 12–19；SELECT 投影在 20–29；FROM/JOIN 在 30–32。 |

Analyzer 對 CASE 的輸出把 `POLICY_STATUS` 也列作計算欄位的依賴，這與 CASE 條件確實會控制結果一致；不過 edge 的一般 source→target 形式沒有標示它是「分支條件」而非日期值來源，閱讀者仍需回到 SQL 區分。Extract 對 `REQUESTED_START_DATE` 在 WHEN/THEN 兩行各列一條 edge，屬同一來源映射的重複證據，不代表兩個不同來源。

## 3. 原始 PCK 行號核對與可確認的資料流程

以下行號均以本次讀取的原始 `.pkb` 檔案逐行核對：

1. **來源抽取與投影** — `PKG_POLICY_EXTRACT.pkb`, `LOAD_SOURCE_TO_STAGE`：
   - 20–21：`SRC_POLICY.POLICY_ID` 投影為 `POLICY_NO`，`POLICY_START_DT` 以 `TO_DATE(TO_CHAR(..., 'YYYY-MM-DD'), 'YYYY-MM-DD')` 投影為 `START_DATE`。
   - 22–26：`REQUESTED_EFFECTIVE_DT` 非 NULL 時做相同日期格式轉換，否則產生 NULL，目標為 `REQUESTED_START_DATE`。
   - 27：投影 `POLICY_STATUS`。
   - 30–32：讀取 `SRC_POLICY`，並以 CUSTOMER_ID、PRODUCT_CODE 分別 JOIN `SRC_CUSTOMER`、`SRC_PRODUCT`。同一 SELECT 於 28–29 對客戶名、產品類型使用 NVL 預設值；這是其他欄位處理，不是 EFFECTIVE_DATE 的來源。
2. **條件計算** — `PKG_POLICY_TRANSFORM.pkb`, `BUILD_TRANSFORMED_ROWS`：
   - 14–19：定義 `CALCULATED_EFFECTIVE_DATE` 等中介目標欄位。
   - 21–31：SELECT CASE 計算欄位。ACTIVE（23–24）採 `START_DATE`；PENDING（25–29）採 `NVL(REQUESTED_START_DATE, START_DATE + 30)`；其他狀態（30）採 `START_DATE`。三個分支都以 TO_CHAR/TO_DATE 做 YYYY-MM-DD 格式往返轉換，結果別名在 31。
   - 35：來源為 `STG_POLICY`。
3. **載入目標** — `PKG_POLICY_LOAD.pkb`, `LOAD_TARGET`：
   - 12–20：MERGE 從 `STG_POLICY_TRANSFORMED` 取得資料。
   - 21：以 POLICY_NO 作為匹配條件。
   - 23：matched update 將 `CALCULATED_EFFECTIVE_DATE` 寫入 `TGT_POLICY.EFFECTIVE_DATE`。
   - 27–39：not matched insert；目標欄位 EFFECTIVE_DATE 在 29，對應值 `s.CALCULATED_EFFECTIVE_DATE` 在 35。

因此欄位值的證據路徑是：

`SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE` →（依 `STG_POLICY.POLICY_STATUS` 分支；PENDING 可改用 `STG_POLICY.REQUESTED_START_DATE` 或 `START_DATE + 30`）→ `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` → `TGT_POLICY.EFFECTIVE_DATE`。

PENDING 的請求日期另有路徑：`SRC_POLICY.REQUESTED_EFFECTIVE_DT` → `STG_POLICY.REQUESTED_START_DATE` → CASE/NVL 計算 → `CALCULATED_EFFECTIVE_DATE`。所以 `EFFECTIVE_DATE` 不保證等於 `POLICY_START_DT`。

## 4. Procedure 呼叫關係與資料流向的區別

本次 Retrieval 找到的三個 Chunk 都是資料處理 Procedure；沒有取回 wrapper Procedure 的呼叫程式碼。原始碼另可確認：

- `PKG_POLICY_LOAD.pkb` 3–8：`RUN_FULL_LOAD` 呼叫 `PKG_POLICY_TRANSFORM.RUN_TRANSFORM`，再呼叫本 Package 的 `LOAD_TARGET`。
- `PKG_POLICY_TRANSFORM.pkb` 3–8：`RUN_TRANSFORM` 呼叫 `PKG_POLICY_EXTRACT.RUN_EXTRACT`，再呼叫本 Package 的 `BUILD_TRANSFORMED_ROWS`。
- `PKG_POLICY_EXTRACT.pkb` 3–6：`RUN_EXTRACT` 呼叫本 Package 的 `LOAD_SOURCE_TO_STAGE`。

所以呼叫圖是 `RUN_FULL_LOAD → RUN_TRANSFORM → RUN_EXTRACT → LOAD_SOURCE_TO_STAGE`，返回後再執行 `BUILD_TRANSFORMED_ROWS`，再返回 `RUN_FULL_LOAD` 執行 `LOAD_TARGET`。這與資料欄位由來源逐層流向目標的概念相關，但不是同一種關係。模型輸出把 `LOAD_SOURCE_TO_STAGE → BUILD_TRANSFORMED_ROWS → LOAD_TARGET` 放在「Procedure 呼叫關係」欄，並不精確：這三個是依資料流程排列的處理步驟，不是彼此直接呼叫。各實際呼叫證據行不在本次送入模型的 sources 中，故問答未能有證據地回答呼叫圖。

## 5. Ollama 回答摘要

以下整理 API 回傳的 `answer` 內容；完整原文逐字附於本報告末尾的附錄。

> 根據提供的證據，以下是對 `TGT_POLICY.EFFECTIVE_DATE` 的欄位分析，涵蓋程式使用位置、各 Package/Procedure 的用途、來源欄位及逐階段轉換規則，並區分資料流向與 Procedure 呼叫關係。
>
> ---
>
> ### 一、欄位 `TGT_POLICY.EFFECTIVE_DATE` 的使用位置與來源
>
> **1. 目標欄位：**  
> `TGT_POLICY.EFFECTIVE_DATE`
>
> **2. 來源欄位：**  
> `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`  
> （見證據 [S1]，`LOAD_TARGET` Procedure，行號 23）
>
> ### 二、資料流向與 Procedure 呼叫關係
>
> #### 1. `PKG_POLICY_LOAD.LOAD_TARGET` Procedure
> - **用途**：將 `STG_POLICY_TRANSFORMED` 中的資料合併至 `TGT_POLICY`。
> - **程式碼範圍**：`PKG_POLICY_LOAD.pkb`，行號 10-40。
> - **關鍵程式碼**：
> ```sql
> t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE
> ```
> - **資料流向**：`STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` → `TGT_POLICY.EFFECTIVE_DATE`
>
> #### 2. `PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS` Procedure
> - **用途**：從 `STG_POLICY` 中計算 `CALCULATED_EFFECTIVE_DATE`，並寫入 `STG_POLICY_TRANSFORMED`。
> - **程式碼範圍**：`PKG_POLICY_TRANSFORM.pkb`，行號 10-36。
> - **關鍵程式碼**：
> ```sql
> CASE
>     WHEN s.POLICY_STATUS = 'ACTIVE'
>         THEN TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
>     WHEN s.POLICY_STATUS = 'PENDING'
>         THEN TO_DATE(
>             TO_CHAR(NVL(s.REQUESTED_START_DATE, s.START_DATE + 30), 'YYYY-MM-DD'),
>             'YYYY-MM-DD'
>         )
>     ELSE TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
> END AS CALCULATED_EFFECTIVE_DATE
> ```
> - **資料流向**：`STG_POLICY.START_DATE`、`STG_POLICY.REQUESTED_START_DATE`、`STG_POLICY.POLICY_STATUS` → `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`
>
> #### 3. `PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE` Procedure
> - **用途**：從 `SRC_POLICY`、`SRC_CUSTOMER`、`SRC_PRODUCT` 載入資料至 `STG_POLICY`。
> - **程式碼範圍**：`PKG_POLICY_EXTRACT.pkb`，行號 8-33。
> - **關鍵程式碼**：
> ```sql
> TO_DATE(TO_CHAR(p.POLICY_START_DT, 'YYYY-MM-DD'), 'YYYY-MM-DD') AS START_DATE
> ```
> ```sql
> CASE
>     WHEN p.REQUESTED_EFFECTIVE_DT IS NOT NULL
>         THEN TO_DATE(TO_CHAR(p.REQUESTED_EFFECTIVE_DT, 'YYYY-MM-DD'), 'YYYY-MM-DD')
>     ELSE NULL
> END AS REQUESTED_START_DATE
> ```
> - **資料流向**：`SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE`; `SRC_POLICY.REQUESTED_EFFECTIVE_DT` → `STG_POLICY.REQUESTED_START_DATE`
>
> ### 三、轉換規則與程式碼片段
>
> #### 1. `CALCULATED_EFFECTIVE_DATE` 計算邏輯（來自 [S2]）
> - ACTIVE 使用 START_DATE；PENDING 使用 REQUESTED_START_DATE 或（若為 NULL）START_DATE + 30；其他狀態使用 START_DATE。
> - 程式使用 `TO_CHAR` 和 `TO_DATE` 進行日期格式轉換，避免直接使用 `DATE` 類型的欄位進行比較。
> - `NVL(s.REQUESTED_START_DATE, s.START_DATE + 30)`：若 REQUESTED_START_DATE 為 NULL，則使用 START_DATE + 30。
>
> ### 四、總結與結論
>
> - 目標欄位來自 `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`。
> - 回答將資料流向列為 `SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE` → `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`。
> - 將 `LOAD_SOURCE_TO_STAGE` → `BUILD_TRANSFORMED_ROWS` → `LOAD_TARGET` 標示為 Procedure 呼叫關係。
> - 程式碼位置列出 [S1] `PKG_POLICY_LOAD.pkb`、[S2] `PKG_POLICY_TRANSFORM.pkb`、[S3] `PKG_POLICY_EXTRACT.pkb`。
>
> ### 五、證據不足的說明
>
> 回答表示目前證據已足夠確認來源與轉換規則，並提示可參考 STG_POLICY 的 START_DATE、REQUESTED_START_DATE 與 POLICY_STATUS。

API 的 `sources` 另外回傳 S1–S3 的 chunkId、檔案、Procedure、startLine/endLine 與 matchedLines。模型 answer 未有完整重述每個 matched line 的原始行號，也未直接引用 S3 的證據代號。完整 answer 字串見末尾附錄。

## 6. 驗收細項

### A. 欄位用途與程式關聯 — PARTIAL

- 正確找出 `LOAD_TARGET` 寫入/更新目標欄位、`BUILD_TRANSFORMED_ROWS` 計算中介欄位、`LOAD_SOURCE_TO_STAGE` 從來源抽取並映射欄位。
- 有完整資料流向的主幹，且三個 Package 都在 Retrieval 結果中。
- 缺漏：問答聲稱 Procedure 呼叫鏈為 `LOAD_SOURCE_TO_STAGE → BUILD_TRANSFORMED_ROWS → LOAD_TARGET`。原碼顯示真正的跨 Package 呼叫由 `RUN_FULL_LOAD`、`RUN_TRANSFORM`、`RUN_EXTRACT` 包裝；上列三個資料處理 Procedure 並非彼此直接呼叫。檢索結果不含 wrapper 程式碼，不能用本次模型 evidence 證成呼叫圖。

### B. 資料轉換與業務規則 — PASS

- ACTIVE、PENDING、ELSE 三分支與 PCK 第 23–30 行相符。
- PENDING 分支 NVL 的兩個輸入及 `+ 30` fallback 正確；原始來源 `REQUESTED_EFFECTIVE_DT` 映射到中介 `REQUESTED_START_DATE`，模型未把中介欄誤稱為原始來源。
- TO_CHAR/TO_DATE 的格式轉換在 Extract 第 21、24 行及 Transform 第 24、27–30 行可驗證。
- 模型多說「避免直接使用 DATE 欄位進行比較」。原始 SQL 沒有日期比較，只有 TO_CHAR/TO_DATE 格式往返；這個理由不受證據支持，是不必要的解釋性推論。核心規則仍正確，因而本項判 PASS 並記錄此局部瑕疵。

### C. 程式碼證據與可追溯性 — PARTIAL

- S1–S3 的 `fileName`、`procedureName`、`startLine/endLine` 與 matchedLines 對應存在的 Package Body；行號 21、23–30 等逐行核對一致。
- 回答含真實檔名及區段行號，但重要結論多只以 `[S#]` 引用，缺少對每項主張逐一列出的實際行號/原始程式碼；Q1 的整條來源到目標路徑分散於三個來源，答覆也沒有以引用行號逐段標出。
- 日期比較推論沒有對應程式碼支持；Procedure 呼叫關係的描述沒有檢索到對應 wrapper 證據。

## 7. 缺漏與主要原因

1. **呼叫圖混入資料流程**：Retrieval 以欄位依賴找出欄位處理 Chunk，沒有同時召回呼叫入口 `RUN_FULL_LOAD`、`RUN_TRANSFORM`、`RUN_EXTRACT`。模型沿資料流程猜成直接呼叫順序，造成關係類型錯誤。
2. **引用格式不保證行級可追溯**：API sources 有 matchedLines，但生成回答未強制每項主張攜帶檔名與原始行號；只有檔案區間和 [S#]，需讀者自行對照。
3. **Analyzer edge 語意有限**：dependencyEdges 將條件欄位、值欄位統一表示為 source→target；CASE 中 POLICY_STATUS 是控制欄位，非日期值。答案用 SQL 自身補足後理解正確，但結構資料尚未明確分類依賴角色。
4. **無法由回答證明所有欄位讀寫點**：本次只查指定目標欄位並使用檢索上限 8；三個 Chunk 足以說明主線，但不是整個 repo 的讀寫盤點或全 Package call-graph 掃描。

## 8. Java 確定性分析與 LLM 協助的界線

**Java / 原始碼可確定取得：**欄位映射邊、表/欄位名稱、Procedure/Package/檔案、Chunk 範圍及 matched line、SQL 中可辨識的 CASE/NVL/TO_CHAR/TO_DATE 文字、已解析的來源→目標路徑。Procedure 呼叫關係也可由原始碼中的呼叫語句確定，但本次 Retrieval 沒有召回那些 wrapper Chunk。

**LLM 協助整理：**把已找到 SQL 證據轉述成繁體中文，摘要分支規則、說明中介欄與資料路徑，並指出 evidence gap。LLM 不應自行補出呼叫圖或推測 SQL 設計理由；此次的 Procedure 關係錯誤和日期比較理由顯示仍須驗證。

## 9. 大量正式 PCK 測試準備度與風險

**結論：具備進入受控測試集的基本條件，但尚不適合將模型輸出直接視為已核實的正式血緣說明。**本次已證明系統可用實際 SQL evidence 檢索出跨三個 Package 的欄位計算與映射，並保留檔名、Procedure、行號資料。正式擴大前建議優先處理：

- 對呼叫關係建立獨立、確定性的證據檢索/輸出，並明確標示 call edge 與 data dependency edge。
- 將引用格式約束成「檔案 / Procedure / 行號 / 程式碼」逐結論呈現，或由後端以來源資料附加而非仰賴模型自行填寫。
- 為 dependency edge 增加角色（值來源、CASE 條件、JOIN 條件、鍵欄位等）及對應 SQL 行。
- 在更大 PCK 集合驗證解析器對別名、巢狀 SQL、動態 SQL、procedure wrapper、例外分支及多語句更新的覆蓋率；目前只有模擬 package，亦未執行 Oracle 編譯/執行驗證。
- 驗收時保留人工逐行核對及「證據不足」狀態，並監測 Prompt 長度與 Retrieval 截斷。

本次只新增驗收報告，未修改正式問答邏輯、Retrieval/Analyzer、Prompt、Vue 或 Evaluation 題目；未使用標準答案，也未進行 Oracle 編譯或資料庫執行。

## 附錄：Ollama `answer` 欄位原始輸出（逐字）

以下是 API 回傳的完整 `answer` 字串；保留模型引用、行號說法及推論，未做修訂：

```text
根據提供的證據，以下是對 `TGT_POLICY.EFFECTIVE_DATE` 的欄位分析，涵蓋程式使用位置、各 Package/Procedure 的用途、來源欄位及逐階段轉換規則，並區分資料流向與 Procedure 呼叫關係。

---

### 一、欄位 `TGT_POLICY.EFFECTIVE_DATE` 的使用位置與來源

**1. 目標欄位：**  
`TGT_POLICY.EFFECTIVE_DATE`

**2. 來源欄位：**  
`STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`  
（見證據 [S1]，`LOAD_TARGET` Procedure，行號 23）

---

### 二、資料流向與 Procedure 呼叫關係

#### 1. `PKG_POLICY_LOAD.LOAD_TARGET` Procedure
- **用途**：將 `STG_POLICY_TRANSFORMED` 中的資料合併至 `TGT_POLICY`。
- **程式碼範圍**：`PKG_POLICY_LOAD.pkb`，行號 10-40。
- **關鍵程式碼**：
  ```sql
  t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE
  ```
- **資料流向**：  
  `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` → `TGT_POLICY.EFFECTIVE_DATE`

---

#### 2. `PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS` Procedure
- **用途**：從 `STG_POLICY` 中計算 `CALCULATED_EFFECTIVE_DATE`，並寫入 `STG_POLICY_TRANSFORMED`。
- **程式碼範圍**：`PKG_POLICY_TRANSFORM.pkb`，行號 10-36。
- **關鍵程式碼**：
  ```sql
  CASE
      WHEN s.POLICY_STATUS = 'ACTIVE'
          THEN TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
      WHEN s.POLICY_STATUS = 'PENDING'
          THEN TO_DATE(
              TO_CHAR(NVL(s.REQUESTED_START_DATE, s.START_DATE + 30), 'YYYY-MM-DD'),
              'YYYY-MM-DD'
          )
      ELSE TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
  END AS CALCULATED_EFFECTIVE_DATE
  ```
- **資料流向**：  
  `STG_POLICY.START_DATE`、`STG_POLICY.REQUESTED_START_DATE`、`STG_POLICY.POLICY_STATUS` → `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE`

---

#### 3. `PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE` Procedure
- **用途**：從 `SRC_POLICY`、`SRC_CUSTOMER`、`SRC_PRODUCT` 載入資料至 `STG_POLICY`。
- **程式碼範圍**：`PKG_POLICY_EXTRACT.pkb`，行號 8-33。
- **關鍵程式碼**：
  ```sql
  TO_DATE(TO_CHAR(p.POLICY_START_DT, 'YYYY-MM-DD'), 'YYYY-MM-DD') AS START_DATE
  ```
  ```sql
  CASE
      WHEN p.REQUESTED_EFFECTIVE_DT IS NOT NULL
          THEN TO_DATE(TO_CHAR(p.REQUESTED_EFFECTIVE_DT, 'YYYY-MM-DD'), 'YYYY-MM-DD')
      ELSE NULL
  END AS REQUESTED_START_DATE
  ```
- **資料流向**：  
  `SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE`  
  `SRC_POLICY.REQUESTED_EFFECTIVE_DT` → `STG_POLICY.REQUESTED_START_DATE`

---

### 三、轉換規則與程式碼片段

#### 1. `CALCULATED_EFFECTIVE_DATE` 計算邏輯（來自 [S2]）
- **條件式轉換**：
  - 若 `POLICY_STATUS = 'ACTIVE'`，則使用 `START_DATE` 作為 `CALCULATED_EFFECTIVE_DATE`。
  - 若 `POLICY_STATUS = 'PENDING'`，則使用 `REQUESTED_START_DATE` 或 `START_DATE + 30`（若 `REQUESTED_START_DATE` 為 `NULL`）。
  - 否則，使用 `START_DATE`。
- **程式碼**：
  ```sql
  CASE
      WHEN s.POLICY_STATUS = 'ACTIVE'
          THEN TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
      WHEN s.POLICY_STATUS = 'PENDING'
          THEN TO_DATE(
              TO_CHAR(NVL(s.REQUESTED_START_DATE, s.START_DATE + 30), 'YYYY-MM-DD'),
              'YYYY-MM-DD'
          )
      ELSE TO_DATE(TO_CHAR(s.START_DATE, 'YYYY-MM-DD'), 'YYYY-MM-DD')
  END AS CALCULATED_EFFECTIVE_DATE
  ```
- **說明**：
  - 使用 `TO_CHAR` 和 `TO_DATE` 進行日期格式轉換，避免直接使用 `DATE` 類型的欄位進行比較。
  - `NVL(s.REQUESTED_START_DATE, s.START_DATE + 30)`：若 `REQUESTED_START_DATE` 為 `NULL`，則使用 `START_DATE + 30`。

---

### 四、總結與結論

| 要點 | 說明 |
|------|------|
| **目標欄位** | `TGT_POLICY.EFFECTIVE_DATE` 來自 `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` |
| **資料流向** | `SRC_POLICY.POLICY_START_DT` → `STG_POLICY.START_DATE` → `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` |
| **轉換邏輯** | 依 `POLICY_STATUS` 異動 `CALCULATED_EFFECTIVE_DATE`，並進行日期格式轉換 |
| **Procedure 呼叫關係** | `PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE` → `PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS` → `PKG_POLICY_LOAD.LOAD_TARGET` |
| **程式碼位置** | [S1] `PKG_POLICY_LOAD.pkb`，[S2] `PKG_POLICY_TRANSFORM.pkb`，[S3] `PKG_POLICY_EXTRACT.pkb` |

---

### 五、證據不足的說明

目前證據已足夠確認 `TGT_POLICY.EFFECTIVE_DATE` 的來源與轉換規則。如需進一步分析 `CALCULATED_EFFECTIVE_DATE` 的上游資料，可參考 `STG_POLICY` 中的 `START_DATE` 和 `REQUESTED_START_DATE`，以及 `POLICY_STATUS` 的來源。
```

