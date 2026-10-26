# Procedure Call Graph × Retrieval／Ollama 整合 POC 報告

## 結論

已將既有 Java Procedure Call Graph Analyzer 接入正式欄位 Retrieval 與 Ollama 問答流程。對 `TGT_POLICY.EFFECTIVE_DATE`，Retrieval 取回 3 個資料處理 Chunk、13 條 SQL dependencyEdges；Call Graph 以這 3 個 Package／Procedure 為種子，經有限深度遍歷取得 5 條已驗證呼叫邊。兩類證據在 Prompt 與回應結構中分開呈現。

最終本機 Ollama `qwen3:8b` 實際問答正確列出五條呼叫及方向，並區分直接來源與上游原始來源。過程中的前兩次 Ollama 回答仍出現來源層級混淆或反向呼叫，因此報告保留這項模型穩定性風險。整合功能已運作，**建議先進行小批量 PCK 試跑並抽樣人工驗收，再擴大到大量正式 PCK**；目前的解析器語法範圍與模型回答一致性尚未在真實企業 Package 驗證。

## 修改檔案

- `backend/src/main/java/com/example/lineage/assistant/LineageAssistantService.java`：欄位 Retrieval 後以命中的 Package／Procedure 作種子，呼叫既有 Graph Service，並將 Call Edges 傳入模型；回應另提供 `procedureCalls`，沒有呼叫邊時新增限制說明。
- `backend/src/main/java/com/example/lineage/assistant/LineageAssistantModel.java`：增加帶 Procedure Call Evidence 的 provider-neutral overload；舊介面呼叫仍可用。
- `backend/src/main/java/com/example/lineage/assistant/AbstractChatClientLineageAssistantModel.java`：共用 Prompt 新增 `Verified Data Dependencies` 與 `Verified Procedure Calls` 區段，加入方向、原始來源／中介欄位及無證據限制。
- `backend/src/main/java/com/example/lineage/assistant/AskResponse.java`：增加獨立 `procedureCalls` 欄位。
- `backend/src/main/java/com/example/lineage/callgraph/ProcedureReference.java`：表示從 Retrieval 得到的 Package／Procedure 種子。
- `backend/src/main/java/com/example/lineage/callgraph/ProcedureCallGraphService.java`：新增雙向鄰接遍歷，使用已驗證 Call Edges 擴展命中程序；依最大深度及邊數限制結果，並以 visited set 偵測循環。
- `backend/src/main/resources/application.yml`：新增 `LINEAGE_ASSISTANT_CALL_GRAPH_MAX_DEPTH`（預設 4）與 `LINEAGE_ASSISTANT_CALL_GRAPH_MAX_EDGES`（預設 12）。Java 同時限制最大值分別為 8 與 32。
- 測試：`LineageAssistantServiceTest.java`、`AbstractChatClientLineageAssistantModelTest.java`，以及新增 `ProcedureCallGraphServiceTest.java`。

沒有修改 Vue、Provider、SQL Dependency Analyzer、Chunking 或既有 Evaluation 標準答案。

## Retrieval 與 Call Graph 整合方式

1. `ChunkRetrievalService.search("TGT_POLICY.EFFECTIVE_DATE", 8)` 先沿用正式資料血緣檢索，取回 `LOAD_TARGET`、`BUILD_TRANSFORMED_ROWS`、`LOAD_SOURCE_TO_STAGE` 三個 Chunk。
2. 這三個命中 Procedure 成為 Graph Service 種子。Graph Service 重用既有 `ProcedureCallAnalyzer`，在原始 `.pkb` 呼叫邊上以 caller/callee 兩向鄰接遍歷，最大深度 4、最多回傳 12 條邊。
3. 已解析邊由 Java 提供；不根據資料流程補邊。Prompt 將資料依賴標成 S 引用、程序呼叫標成 C 引用，並另保留原 SQL Chunk。
4. `AskResponse.procedureCalls` 回傳相同已驗證邊，讓呼叫證據不只存在於模型自然語言文字。
5. 無檢索證據時不執行圖擴展；無呼叫邊時 Prompt 要求回答「無法確認直接呼叫關係」。

## 最終 Ollama 測試環境與實際證據數量

- 測試模型：本機 Ollama `qwen3:8b`；沒有呼叫 Gemini、OpenAI 或其他雲端模型。
- 本次修改版 Spring Boot 暫以 `8082` 啟動；既有其他埠服務未停止。測試完成後已停止本次啟動的程序。
- Retrieval limit：8（正式設定）。
- Retrieval 回傳：3 個 Chunk，13 條 SQL dependencyEdges。
- Call Graph 擴展：5 條已驗證呼叫邊，包含 3 條同 Package、2 條跨 Package 邊。
- 最終 Ollama API 往返耗時：**17,038 ms**。
- 每次呼叫均是單一指定欄位問答；Prompt 沒有加入 ground truth 或 expected answer。

最終送入 `/api/assistant/ask` 的 request：

```json
{
  "question": "請分析 TGT_POLICY.EFFECTIVE_DATE 的資料血緣，分別說明直接來源與原始來源欄位及轉換條件。再列出檢索證據中已驗證的 Procedure 直接呼叫關係。請逐條照抄每條呼叫證據的 Caller → Callee 方向及其引用，不可反轉或根據資料流猜測呼叫；每項結論附 PCK 檔名與原始行號。",
  "column": "TGT_POLICY.EFFECTIVE_DATE"
}
```

### 實際送入 Prompt 的 Verified Procedure Calls

以下是 Prompt 格式器依本次 Retrieval 種子及既有 Java Analyzer 結果產生的呼叫證據（欄位順序與內容對應 Ollama 回應的 `procedureCalls`）：

```text
[C1] CALLER PKG_POLICY_LOAD.RUN_FULL_LOAD -> PKG_POLICY_LOAD.LOAD_TARGET; file=PKG_POLICY_LOAD.pkb; line=7; code=LOAD_TARGET;
[C2] CALLER PKG_POLICY_TRANSFORM.RUN_TRANSFORM -> PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS; file=PKG_POLICY_TRANSFORM.pkb; line=7; code=BUILD_TRANSFORMED_ROWS;
[C3] CALLER PKG_POLICY_EXTRACT.RUN_EXTRACT -> PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE; file=PKG_POLICY_EXTRACT.pkb; line=5; code=LOAD_SOURCE_TO_STAGE;
[C4] CALLER PKG_POLICY_LOAD.RUN_FULL_LOAD -> PKG_POLICY_TRANSFORM.RUN_TRANSFORM; file=PKG_POLICY_LOAD.pkb; line=6; code=PKG_POLICY_TRANSFORM.RUN_TRANSFORM;
[C5] CALLER PKG_POLICY_TRANSFORM.RUN_TRANSFORM -> PKG_POLICY_EXTRACT.RUN_EXTRACT; file=PKG_POLICY_TRANSFORM.pkb; line=6; code=PKG_POLICY_EXTRACT.RUN_EXTRACT;
```

欄位依賴證據則在另一個 `Verified Data Dependencies` 區段；其主要鏈為：

```text
SRC_POLICY.POLICY_START_DT -> STG_POLICY.START_DATE
SRC_POLICY.REQUESTED_EFFECTIVE_DT -> STG_POLICY.REQUESTED_START_DATE
STG_POLICY.START_DATE / REQUESTED_START_DATE / POLICY_STATUS -> STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE
STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE -> TGT_POLICY.EFFECTIVE_DATE
```

Transform 的 POLICY_STATUS 邊是 CASE 分支條件依賴，不代表 POLICY_STATUS 的值被寫成日期。Prompt 指示模型區分控制依賴與欄位值依賴。

## Ollama 最終原始回答

以下保留最後一輪 API `answer` 欄位原文：

```text
### 一、TGT_POLICY.EFFECTIVE_DATE 的資料血緣分析

#### 1. **直接來源欄位**
根據證據，TGT_POLICY.EFFECTIVE_DATE 的直接來源欄位是：
- **STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE**

此欄位在 [S1] 的 SQL 中被直接寫入 TGT_POLICY.EFFECTIVE_DATE。

#### 2. **原始來源欄位**
沿著已驗證的依賴邊，TGT_POLICY.EFFECTIVE_DATE 的原始來源欄位是：
- **SRC_POLICY.REQUESTED_EFFECTIVE_DT**（透過 STG_POLICY.REQUESTED_START_DATE 轉換）
- **SRC_POLICY.POLICY_START_DT**（透過 STG_POLICY.START_DATE 轉換）

#### 3. **轉換條件**
根據 [S2] 的 SQL 中的 CASE 條件，STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE 的計算方式如下：
- 若 STG_POLICY.POLICY_STATUS = 'ACTIVE'，則使用 STG_POLICY.START_DATE。
- 若 STG_POLICY.POLICY_STATUS = 'PENDING'，則使用 STG_POLICY.REQUESTED_START_DATE 或 STG_POLICY.START_DATE + 30。
- 否則，使用 STG_POLICY.START_DATE。

這些值在 [S3] 中從 SRC_POLICY 載入 STG_POLICY，其中：
- STG_POLICY.START_DATE 來自 SRC_POLICY.POLICY_START_DT。
- STG_POLICY.REQUESTED_START_DATE 來自 SRC_POLICY.REQUESTED_EFFECTIVE_DT。

---

### 二、檢索證據中已驗證的 Procedure 直接呼叫關係

#### 1. [C1]  
**Caller → Callee**: `PKG_POLICY_LOAD.RUN_FULL_LOAD → PKG_POLICY_LOAD.LOAD_TARGET`  
**檔名與行號**: `PKG_POLICY_LOAD.pkb; line=7`  
**Code**: `LOAD_TARGET`

#### 2. [C2]  
**Caller → Callee**: `PKG_POLICY_TRANSFORM.RUN_TRANSFORM → PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS`  
**檔名與行號**: `PKG_POLICY_TRANSFORM.pkb; line=7`  
**Code**: `BUILD_TRANSFORMED_ROWS`

#### 3. [C3]  
**Caller → Callee**: `PKG_POLICY_EXTRACT.RUN_EXTRACT → PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE`  
**檔名與行號**: `PKG_POLICY_EXTRACT.pkb; line=5`  
**Code**: `LOAD_SOURCE_TO_STAGE`

#### 4. [C4]  
**Caller → Callee**: `PKG_POLICY_LOAD.RUN_FULL_LOAD → PKG_POLICY_TRANSFORM.RUN_TRANSFORM`  
**檔名與行號**: `PKG_POLICY_LOAD.pkb; line=6`  
**Code**: `PKG_POLICY_TRANSFORM.RUN_TRANSFORM`

#### 5. [C5]  
**Caller → Callee**: `PKG_POLICY_TRANSFORM.RUN_TRANSFORM → PKG_POLICY_EXTRACT.RUN_EXTRACT`  
**檔名與行號**: `PKG_POLICY_TRANSFORM.pkb; line=6`  
**Code**: `PKG_POLICY_EXTRACT.RUN_EXTRACT`

---

### 三、總結
- **TGT_POLICY.EFFECTIVE_DATE 的直接來源為 STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE**。
- **原始來源為 SRC_POLICY.REQUESTED_EFFECTIVE_DT 和 SRC_POLICY.POLICY_START_DT**。
- **轉換條件**：根據 STG_POLICY.POLICY_STATUS 的值，使用 START_DATE 或 REQUESTED_START_DATE（或 START_DATE + 30）計算 CALCULATED_EFFECTIVE_DATE。
- **已驗證的 Procedure 呼叫關係**已逐條列出，並附上 PCK 檔名與行號。
```

最終答覆的五條 caller→callee 方向及檔名／行號皆符合 Java Analyzer 證據；原始來源已追溯到 `SRC_POLICY.POLICY_START_DT` 與 `SRC_POLICY.REQUESTED_EFFECTIVE_DT`，CASE/NVL 分支也正確。回答沒有逐條引用資料映射的具體原始行號（主要使用 [S#]），但後端 `sources` 附有 `fileName`、`procedureName`、start/end line 及 matchedLines；Procedure Call 區段則有具體檔名與行號。

## 驗收結果

| 檢查項目 | 結果 | 依據 |
|---|---|---|
| 原有資料血緣 | PASS | Retrieval 仍找到 LOAD、TRANSFORM、EXTRACT 三段；直接欄位映射及原始來源鏈存在。既有 Retrieval 測試通過。 |
| 五條 Call Edge 進入問答證據 | PASS | 最終 Prompt 的 `Verified Procedure Calls` 列出五條 Java Analyzer 邊，含 Caller、Callee、PCK、原始行號、原始呼叫程式碼。API 回應 `procedureCalls` 亦是五條。 |
| 不把資料流當成直接呼叫 | PASS（最終一輪） | 最終 Ollama 回答將資料血緣與直接呼叫分節，且五條 call edge 方向皆正確；前兩輪曾出現問題，見下方風險。 |
| 真正呼叫關係 | PASS（最終一輪） | LOAD→TRANSFORM、TRANSFORM→EXTRACT 及各 package 內部呼叫均按 Analyzer 結果列出。 |
| 檔名與行號可追溯 | PARTIAL | Call Edge 回答逐條附檔名與行號；Data lineage answer 主要引用 [S#]，詳細映射行號需查看 API `sources.matchedLines`。 |
| 有界擴展與循環偵測 | PASS | 單元測試驗證最大深度、最大邊數及循環圖只回傳唯一邊；正式預設為深度 4、最多 12 邊。 |
| 無呼叫證據不捏造 | PASS（程式證據限制） | Prompt 空證據路徑明確要求「無法確認直接呼叫關係」；單元測試檢查此文字。模型仍非形式化保證，需在不同提問上抽測。 |
| 既有 Maven 測試 | PASS | `mvn test`：48 tests，0 failures，0 errors，0 skipped。包含既有 Retrieval 測試與新增 Call Graph／Prompt 整合測試。 |

## 端對端迭代觀察

本次有三次本機 Ollama 單題問答，沒有標準答案輸入：

1. 第一輪（30,554 ms）：Call Edge 五條方向正確；但把中介欄稱為原始來源，後續才列出 SRC_POLICY 上游欄位。
2. 第二輪（21,067 ms）：來源仍混淆，並把兩條跨 Package Call Edge 的方向倒置，且把先後執行描述成呼叫關係。該版本不符合驗收。
3. 最終輪（17,038 ms）：在 Prompt 明確要求原樣保留 Caller→Callee 方向及 C 引用後，五條 Call Edge、欄位直接／原始來源與轉換條件皆符合證據；未將資料流向說成 Procedure 呼叫。

不同輪次答案不一致，代表模型表現會受問法及生成隨機性影響。最終單輪通過不能視為模型具備穩定保證；Call Graph JSON 是確定性依據，生成文字仍須抽樣評估。

## 尚未解決的問題與後續建議

- Retrieval 的 SQL dependencyEdges 尚未把「值來源」與「CASE 控制依賴」做型別化；Prompt 能說明差異，但目前仍需要模型正確解讀 SQL。
- Call Graph 目前支援範圍仍沿用 POC Analyzer：獨立直接呼叫、已知且唯一的 body Procedure。動態 SQL、多行／內嵌呼叫、間接呼叫及 overload 無法完整解析。
- 邊擴展是以相關 Procedure 種子沿 Call Graph 兩向走訪，深度 4、最多 12 條；大型 PCK 套件可能需要按問題種類或呼叫方向縮小上下文，並回報被上限截斷的狀態。
- 回答中的 Data Lineage 引用目前沒有強制把每個 S 引用改寫成檔名加具體行號。可由 API `sources` 查證，但如果要求自然語言答案本身逐主張可追溯，下一步應由後端輸出結構化引用或做回答後驗證。
- 進入大量 PCK 驗證前，建議先以數個具有 package wrapper、同名程序、條件分支及動態呼叫的真實化樣本做人工標註比較；不應直接把此三個模擬 Package 的測試通過率外推至正式程式庫。
