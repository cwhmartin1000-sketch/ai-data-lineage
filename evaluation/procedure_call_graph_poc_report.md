# Procedure Call Graph POC 報告

## 摘要

已建立獨立的 Java Procedure Call Analyzer 與 `GET /api/call-graph` 查詢 API。Analyzer 讀取既有 Chunking Service 載入的 `.pkb` Procedure 區塊，從原始程式碼辨識可解析且唯一的直接呼叫，並保留呼叫端、被呼叫端、檔名、原始行號與呼叫程式碼。

本次模擬 PCK 找出全部 **5 條**預期呼叫，未將 SQL 欄位資料依賴當成 Procedure Call Edge。完整 Maven 測試結果為 **43 tests, 0 failures, 0 errors, 0 skipped**。

## 實作方式與修改檔案

- `backend/src/main/java/com/example/lineage/callgraph/ProcedureCallEdge.java`：Call Edge 資料模型，欄位包含 caller/callee Package 與 Procedure、檔名、行號、原始證據程式碼，以及 `crossPackage` 分類。
- `backend/src/main/java/com/example/lineage/callgraph/UnresolvedProcedureCall.java`、`ProcedureCallAnalysis.java`：記錄無法唯一解析的呼叫候選及分析結果，不將不確定關係偽裝成確定 Call Edge。
- `backend/src/main/java/com/example/lineage/callgraph/ProcedureCallAnalyzer.java`：確定性分析器。重用 `PlsqlChunkingService` 的 Package／Procedure 切塊與行號；建立 body Procedure 符號表後，解析獨立呼叫語句。
- `backend/src/main/java/com/example/lineage/callgraph/ProcedureCallGraphService.java`：載入當前 Package chunks、分析並依 caller Package／Procedure 篩選。
- `backend/src/main/java/com/example/lineage/api/ProcedureCallGraphController.java`：提供 `/api/call-graph` 查詢。
- `backend/src/test/java/com/example/lineage/callgraph/ProcedureCallAnalyzerTest.java`：驗證五條實際呼叫、行號、同／跨 Package 分類，以及註解、字串常值與 overload 模糊情況。
- `backend/src/test/java/com/example/lineage/api/ProcedureCallGraphControllerTest.java`：驗證 API query parameter、輸出欄位及邊篩選。

沒有修改 Chunking、SQL Dependency Analyzer、Data Lineage Retrieval、Prompt、Provider、Vue 或既有 Evaluation 題目。

## Analyzer 找到的 Call Edges

以下行號以實際 `.pkb` 原始檔核對；`evidenceCode` 是該行呼叫敘述原文。

| Caller | Callee | 類型 | 證據 |
|---|---|---|---|
| `PKG_POLICY_LOAD.RUN_FULL_LOAD` | `PKG_POLICY_TRANSFORM.RUN_TRANSFORM` | 跨 Package | `packages/PKG_POLICY_LOAD.pkb:6` — `PKG_POLICY_TRANSFORM.RUN_TRANSFORM;` |
| `PKG_POLICY_LOAD.RUN_FULL_LOAD` | `PKG_POLICY_LOAD.LOAD_TARGET` | 同 Package | `packages/PKG_POLICY_LOAD.pkb:7` — `LOAD_TARGET;` |
| `PKG_POLICY_TRANSFORM.RUN_TRANSFORM` | `PKG_POLICY_EXTRACT.RUN_EXTRACT` | 跨 Package | `packages/PKG_POLICY_TRANSFORM.pkb:6` — `PKG_POLICY_EXTRACT.RUN_EXTRACT;` |
| `PKG_POLICY_TRANSFORM.RUN_TRANSFORM` | `PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS` | 同 Package | `packages/PKG_POLICY_TRANSFORM.pkb:7` — `BUILD_TRANSFORMED_ROWS;` |
| `PKG_POLICY_EXTRACT.RUN_EXTRACT` | `PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE` | 同 Package | `packages/PKG_POLICY_EXTRACT.pkb:5` — `LOAD_SOURCE_TO_STAGE;` |

呼叫結構如下。箭頭代表程式碼中實際的 Procedure 呼叫；它不代表欄位值的資料依賴：

```text
PKG_POLICY_LOAD.RUN_FULL_LOAD
  ├─calls→ PKG_POLICY_TRANSFORM.RUN_TRANSFORM
  │          ├─calls→ PKG_POLICY_EXTRACT.RUN_EXTRACT
  │          │          └─calls→ PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE
  │          └─calls→ PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS
  └─calls→ PKG_POLICY_LOAD.LOAD_TARGET
```

API 範例：

```http
GET /api/call-graph?package=PKG_POLICY_LOAD&procedure=RUN_FULL_LOAD
```

回傳物件包含 `edges` 與 `unresolvedCalls`。每條 `edges` 含 `callerPackage`、`callerProcedure`、`calleePackage`、`calleeProcedure`、`fileName`、`lineNumber`、`evidenceCode`、`crossPackage`。`unresolvedCalls` 提供可辨識但無法唯一解析的候選語句及原因。不帶參數時列出所有已解析和未解析的直接呼叫候選；`package` 與 `procedure` 篩選呼叫端。

## 與 Data Lineage 的區分

- **Procedure Call Edge**：例如 `PKG_POLICY_LOAD.RUN_FULL_LOAD` 呼叫 `PKG_POLICY_TRANSFORM.RUN_TRANSFORM`，證據是 PL/SQL 可執行區塊中的 Procedure 呼叫敘述。
- **Data Lineage Edge**：例如 `STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE` 寫入 `TGT_POLICY.EFFECTIVE_DATE`，證據是 MERGE/INSERT/SELECT 欄位映射。

本次 Call Graph 只從 `.pkb` 程式呼叫敘述產生，不消費或轉換 `SqlColumnDependencyAnalyzer` 的欄位邊，因此不會將 Extract → Transform → Load 的資料流順序直接當作 Procedure 呼叫邊。

## 測試結果

使用命令：

```powershell
cd backend
mvn test
```

結果：**BUILD SUCCESS**，43 tests，0 failures，0 errors，0 skipped。新增的三個 Analyzer 測試與一個 Controller 測試均通過。既有 Chunking、Chunk Retrieval、SQL Dependency Analyzer 使用的 Retrieval 測試也通過；離線 Retrieval 評估測試回報 12 題、8 題計分、micro recall 100%、Archive false positives 0。

測試還包含下列負例：

- `--` 行註解及 `/* ... */` 區塊註解中的假呼叫不產生邊。
- 字串常值中的 `PROC_NAME;` 不產生邊。
- 未在 Package body 中唯一解析的目標 Procedure 不產生邊，並列於 `unresolvedCalls`。
- 同名 Procedure overload 不根據名稱猜測目標；候選列於 `unresolvedCalls`，並註明 overload resolution 尚未支援。
- 對目前三個模擬 Package，找到的五條邊與手動核對的實際呼叫一致，未觀察到 False Positive 或 False Negative。這項結論只適用於本次模擬來源與已支援語法。

## 支援範圍與限制

**目前支援：**

- `.pkb` 中可由 Chunking Service 可靠標記 Package 與 Procedure 的程式區塊。
- 獨立一行的直接 Procedure 呼叫，包括 `PACKAGE.PROCEDURE;` 與同 Package `PROCEDURE;`。
- 同一行括號參數呼叫（若存在）以及跨 Package／同 Package 呼叫。
- 只有被呼叫 Package/Procedure 在 body 中唯一宣告時才建立邊；重複名稱／overload 不猜測。
- 行註解、區塊註解與單引號字串會遮罩後再辨識，行號與 evidenceCode 保留原始內容。

**目前不支援或無法確認：**

- 動態 SQL、透過變數/表資料間接派送、反射式呼叫、callback、function 呼叫與 SQL 中的函式使用。
- 多行參數呼叫、與其他敘述寫在同一行的呼叫、巢狀控制結構同一行呼叫、標籤或特殊編譯器語法。
- 引號識別字、Oracle q-quoted 字串、Package 初始化區塊、匿名 PL/SQL 區塊及沒有可靠 Procedure 範圍的程式碼。
- 參數簽章／型別相容性檢查；若名稱唯一但呼叫參數與宣告不相容，現階段不能做完整編譯器等級驗證。
- `.pks` 只含宣告，不作為呼叫證據來源。

可辨識但無法唯一解析的獨立呼叫候選會列在 `unresolvedCalls`，不建立 Call Edge。動態 SQL 與其他不符合支援語法的形式未必能抽出具體候選，故仍可能造成未標記的 False Negative。呼叫表面符合單行規則但語意受動態解析影響時，也可能超出此簡化 POC 的判斷能力。大規模套用前應增加不支援語法掃描診斷，並與 Oracle parser/compiler 或人工審查做抽樣比對。

## 下一步 Retrieval 整合建議

目前 API 與 Analyzer 可獨立驗證，**已準備好進行下一步受控 Retrieval 整合**，但尚未整合到問答 Prompt。整合時建議：

1. 將 Call Edge 作為獨立的 `procedureCallEdges` 區段提供，不與 `dependencyEdges` 合併。
2. 欄位問題優先保留欄位 lineage chunks；若回答要求呼叫關係，再補入相關 caller/callee Procedure chunks，並設有限量。
3. Prompt 明確區分「呼叫證據」與「欄位映射證據」；模型只能摘要，呼叫圖與行號由 Java analyzer/API 定義。
4. 在來源證據中包含每條呼叫的檔名、行號及原始 evidenceCode，並測試不要將資料流向誤述成直接呼叫。
5. 對未解析呼叫提供「無法由目前支援語法確認」狀態，不讓模型推補。

POC 還未在真實企業 Package 或 Oracle 資料庫編譯結果上驗證。

