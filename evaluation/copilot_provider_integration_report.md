# GitHub Copilot Provider 整合報告

日期：2026-10-24

## 摘要

已新增 GitHub 官方 Copilot SDK for Java Adapter，透過 Spring 設定的 `COPILOT_CLI_URL` 連線至本機 Copilot CLI headless server。原本的 Ollama Adapter 與設定保留，兩者共用既有問答服務、Retrieval、SQL Dependency Analyzer、Procedure Call Graph 與 Prompt Grounding。

採用 Maven Artifact `com.github:copilot-sdk-java:1.0.22`。截至本報告日期，官方 Java SDK 文件列出的最新非 preview 版為 1.0.22；另有 preview 版本。官方 SDK 仍標示為 Public Preview，API 可能變更。SDK 文件要求 Java 17 或以上，因此本專案 Java 21 符合需求。請參閱[官方 Java SDK 文件](https://github.github.com/copilot-sdk-java/)、[Java SDK README](https://github.com/github/copilot-sdk/blob/main/java/README.md)與[SDK 相容性說明](https://docs.github.com/en/copilot/how-tos/copilot-sdk/troubleshooting/compatibility)。

## 修改檔案

- `backend/pom.xml`：加入官方 Java SDK 1.0.22。
- `backend/src/main/resources/application.yml`：加入 CLI URL、模型及請求 Timeout 設定。
- `backend/src/main/java/com/example/lineage/assistant/AssistantProviderEnvironmentPostProcessor.java`：允許 `copilot` Provider，僅要求 Copilot CLI URL 與模型設定。
- `backend/src/main/java/com/example/lineage/assistant/AssistantModelConfiguration.java`：選擇 Copilot 時建立 SDK Client，指定外部 CLI URL，關閉 SDK 自動啟動 CLI，並限制 Timeout 為 1 至 600 秒。
- `backend/src/main/java/com/example/lineage/assistant/AbstractChatClientLineageAssistantModel.java`：將共用系統指示設為 package 內可重用，提示格式器與內容不變。
- `backend/src/main/java/com/example/lineage/assistant/CopilotLineageAssistantModel.java`：新增官方 SDK Adapter。
- `backend/src/main/java/com/example/lineage/api/AssistantProviderController.java`：顯示所選 Copilot 模型名稱。
- `backend/src/test/java/com/example/lineage/assistant/AssistantProviderEnvironmentPostProcessorTest.java`：覆蓋 Provider 對應與必要設定。
- `backend/src/test/java/com/example/lineage/assistant/AssistantModelConfigurationTest.java`：覆蓋 Copilot/Ollama Provider 選擇與 Timeout 範圍。
- `backend/src/test/java/com/example/lineage/assistant/CopilotLineageAssistantModelTest.java`：驗證 Prompt 證據、無工具權限、隔離 Session、錯誤與 Timeout。
- `backend/src/test/java/com/example/lineage/api/AssistantProviderControllerTest.java`：驗證 Provider 資訊不輸出類似憑證的設定。
- `README.md`：加入 Copilot 安裝、登入及啟動設定。

未修改 Vue、Chunking、Retrieval 演算法、SQL Dependency Analyzer、Procedure Call Graph 或模擬 PCK。

## Provider 與資料流

```text
POST /api/assistant/ask
  -> 既有 LineageAssistantService
  -> 既有 Chunk Retrieval + SQL Dependency Analyzer + Call Graph
  -> 共用 Structured Prompt（SQL Dependencies、Procedure Calls、PCK Chunk）
  -> LineageAssistantModel
       ├─ Ollama Adapter（原有）
       └─ Copilot Adapter（新增）
            -> 單一外部本機 CLI headless server
            -> 每次請求建立隔離 Session、取回答、刪除 Session
```

使用 `LINEAGE_ASSISTANT_PROVIDER=copilot` 時，Spring AI ChatModel 選擇仍為 `none`；Copilot SDK 在共用 `LineageAssistantModel` 抽象下執行，不會建立另一套檢索或分析流程。`ollama` 選擇與 `OLLAMA_BASE_URL`、`OLLAMA_CHAT_MODEL` 不變。

Adapter 不在每個請求啟動 CLI。它使用 `COPILOT_CLI_URL` 連線到由使用者另行啟動的 CLI server，並在 Spring 元件生命週期中重用 SDK Client。每個請求使用隨機 Session ID，完成後關閉並要求 CLI 刪除該 Session，避免跨問題重用對話上下文。

Session 使用空工具模式，工具、MCP Server、自訂 Agent 清單皆為空；另停用設定探索、Skills、自訂指令與 on-demand 指令探索，並明確拒絕所有 permission request。SDK 不會收到專案工作目錄設定。請求只把既有格式器建立的提問與檢索證據交給 Copilot。

請求總 Timeout 可由 `COPILOT_TIMEOUT_SECONDS` 設定，範圍 1–600 秒，預設 60 秒。連線失敗回報 CLI server 不可用；建立 Session 或模型請求失敗回報 Copilot 請求錯誤；Timeout 回報設定名稱。錯誤訊息不會附上 Token、請求內容或 SDK 原始錯誤細節。Session 清理最多額外等待 2 秒。

## 官方 CLI 安裝、OAuth 登入與驗證（Windows PowerShell）

依 GitHub 官方文件，Windows 可使用 WinGet 安裝；官方 CLI OAuth 登入命令為 `copilot login`，登入由使用者在瀏覽器完成。參見[官方安裝文件](https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/install-copilot-cli)與[官方驗證文件](https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli)。組織管理員若停用 CLI 或限制可用模型，使用者須依組織政策處理；本 Adapter 不繞過這些限制。

```powershell
winget install GitHub.Copilot
copilot --version
copilot login
```

完成登入後，在一個 PowerShell 視窗啟動本機 headless server，保持視窗執行：

```powershell
copilot --headless --port 4321
```

SDK 連線位置採 loopback：`http://localhost:4321`。不要把 headless CLI server 暴露到公網。SDK Client 使用外部 CLI URL，不會為每個 HTTP 問答重新啟動 CLI。

## 啟動方式

在另一個 PowerShell 視窗：

```powershell
$env:LINEAGE_ASSISTANT_PROVIDER = "copilot"
$env:COPILOT_CLI_URL = "http://localhost:4321"
$env:COPILOT_CHAT_MODEL = "<此 Copilot 帳號可用的模型名稱>"
$env:COPILOT_TIMEOUT_SECONDS = "60"
Set-Location "C:\Martin\project\ai-data-lineage-assistant\ai-data-lineage\backend"
mvn spring-boot:run
```

可用模型由 Copilot 帳號、方案及組織政策決定；本專案不預設某一模型一定可用。模型名稱無法使用時，API 會回傳清楚錯誤，不會改用其他 Provider。

查看後端選擇的 Provider 與模型設定：

```powershell
Invoke-RestMethod http://localhost:8080/api/assistant/provider
```

問答 API 維持原格式：

```powershell
$body = @{
  question = "TGT_POLICY.EFFECTIVE_DATE 的原始來源、檢核條件與 Procedure 呼叫方向為何？"
  column   = "TGT_POLICY.EFFECTIVE_DATE"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/assistant/ask" `
  -ContentType "application/json" `
  -Body $body
```

切回原有 Ollama：

```powershell
$env:LINEAGE_ASSISTANT_PROVIDER = "ollama"
$env:OLLAMA_BASE_URL = "http://localhost:11434"
$env:OLLAMA_CHAT_MODEL = "qwen3:8b"
$env:OLLAMA_THINK = "false"
mvn spring-boot:run
```

切換 Provider 只改 Spring 環境設定，不修改 Retrieval、Call Graph、Prompt 或 Vue。

## 測試結果

執行命令：`mvn test`（Java 21，Spring Boot 3.5.16、Spring AI 1.1.8）。

- 結果：**59 tests，0 failures，0 errors，0 skipped**。
- Maven：**BUILD SUCCESS**。
- 新增 Copilot Adapter 的 Mock SDK 測試：3 項通過。
- 新增 Copilot Provider 設定／Timeout 測試：2 項通過。
- 原有 Ollama、問答服務、Retrieval、Dependency Analyzer、Call Graph 等測試全數通過。
- 離線 Retrieval 測試輸出維持既有結果：12 題中 8 題有可評分目標；套件檔案 Recall 100%，Archive 干擾誤判 0。
- 編譯時 Copilot SDK 的 annotation processors 顯示其宣告 source level 為 Java 17，低於本專案 Java 21；目前編譯與測試成功，這是 SDK 公開預覽階段的非阻斷警告，升級 SDK 時應再確認。

Mock SDK 測試確認共用 `Verified SQL Dependencies`、`Verified Procedure Calls` 與 `[S#]` Chunk 證據進入 Prompt；兩次呼叫使用不同 Session ID；Session 無工具、無 MCP、無自訂 Agent；permission callback 的結果為拒絕；CLI 連線錯誤及請求 Timeout 有明確處理。這些測試不代表已連線 GitHub Copilot。

## 真實 Copilot 問答與 Ollama 比較

**狀態：SKIPPED，沒有執行模型請求。** 本次環境未找到 `copilot` 可執行命令，`localhost:4321` 也沒有 CLI server listener，因此無法確認安裝或 OAuth 登入狀態。依要求，未執行 `copilot login`、未呼叫 Gemini/OpenAI，也沒有以 Mock 輸出冒充真實回答。

| 指標 | GitHub Copilot | Ollama `qwen3:8b` |
|---|---|---|
| 模型名稱 | 未提供／未執行 | 本次未呼叫 |
| API/CLI 連線 | SKIPPED：CLI/server 不存在 | 未呼叫 |
| 回答、耗時 | 無 | 無 |
| 來源、條件、行號評分 | 無法評分 | 無法比較 |

使用者登入並啟動 CLI 後，可用相同問題與相同欄位經由 `/api/assistant/ask` 進行手動真實驗證。此報告不宣稱 Copilot 與 Ollama 的回答品質差異。

## 資安注意事項與限制

- Copilot 使用雲端模型；問題與 Retrieval 所選中的 PCK Chunk 會傳送至 GitHub Copilot 服務。此專案只使用虛構程式碼驗證；傳送公司程式碼前，需取得公司核准並確認 GitHub Copilot 組織政策及資料處理規則。
- OAuth 由官方 CLI 管理；本專案不接收密碼或 Token，也不設定 SDK GitHub Token。
- 不要在 Vue、`application.yml`、原始碼、Git 或日誌加入 Token／密碼。CLI 登入狀態儲存在本機 CLI 管理的設定中。
- SDK 的工具限制降低檔案與 Shell 操作能力；仍應維持 CLI server 僅綁定本機、持續更新官方 SDK/CLI，並在升級 SDK 時重跑限制測試。
- Java SDK 是 Public Preview；SDK 和 CLI 通訊協定可能有不相容變更。企業正式導入前應先固定經驗證的 SDK/CLI 版本並重新驗證模型可用性、組織政策、Timeout 與安全設定。
- `GET /api/assistant/provider` 回報的是啟動設定是否完整，不是 OAuth 或 CLI 連線健康檢查。真實連線狀態會在首次問答連線時確認；若需求包含啟動時連線探測，需另行設計健康檢查流程。

## 結論

程式與設定整合完成，**正式 `POST /api/assistant/ask` 已能選到 Copilot Adapter 並沿用目前的證據流程**；完整 Maven 測試通過。由於本機未安裝／啟動 Copilot CLI，本次尚未完成登入後的真實端對端問答，故目前不能宣稱 Copilot 已實際回答成功。先依上方指令安裝、登入與啟動 CLI，再以模擬 PCK 進行真實驗證。
