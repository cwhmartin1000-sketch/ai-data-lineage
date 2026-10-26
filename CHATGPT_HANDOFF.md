# AI Data Lineage Assistant 專案交接摘要

請先閱讀這份摘要與專案 README，再依照我新的要求處理。以下是專案背景與目前狀態，不代表要自動執行任何新工作。

## 專案目標

建立一個以 PL/SQL Package 原始碼為證據的「資料轉換助理」，協助分析欄位來源、跨 Package 資料轉換、條件規則、Procedure 呼叫關係及原始碼行號。只使用程式碼證據回答；資料流向不能推定為 Procedure 呼叫關係。

## 技術與主要結構

- Java 21、Maven、Spring Boot 3.5.x、Spring AI 1.1.x。
- Vue 3 + Vite 前端。
- Oracle PL/SQL 虛構測試 Package 位於 `packages/`。
- 後端入口在 `backend/`；評估與 POC 文件位於 `evaluation/`。
- `LINEAGE_PACKAGES_PATH` 可指定外部 Package 根目錄；未設定時使用專案內模擬資料。
- Chunking 在 Spring Boot 啟動時掃描來源，按 Procedure／Function 切分，保存行號及原始程式碼並快取於記憶體。修改來源後需重啟後端。
- Retrieval 使用精確欄位識別碼匹配，再沿 SQL Dependency Analyzer 產生的欄位依賴邊追蹤；不使用 evaluation 標準答案作為正式檢索來源。
- Procedure Call Graph 由 Java 從 PL/SQL 直接呼叫語句解析，與 Data Dependency 分開呈現。
- Structured Prompt 分開提供 Verified Data Dependencies、Verified Procedure Calls 與原始 SQL Chunk。
- 問答 API：`POST /api/assistant/ask`。

## AI Provider

已有 OpenAI、Gemini、Ollama 與 GitHub Copilot Provider Adapter。Copilot 使用官方 Java SDK 連接本機已登入的官方 Copilot CLI。OAuth 登入已成功；SDK 曾確認 CLI 帳號已驗證，模型清單只回傳 `auto`。

最近一次 Copilot 問答 API 呼叫因 GitHub 回報帳號本月用量額度已超出而回傳 HTTP 502，未取得模型回答。測試使用的是專案虛構 PCK；Spring Boot 與 Copilot CLI server 在測試後已停止。這不代表登入或本機 CLI/SDK 連線失敗。

## 最近變更

新增可選的完整 RAG Prompt 日誌：

```powershell
$env:LINEAGE_ASSISTANT_LOG_PROMPT = "true"
```

此設定會讓後端以 INFO 記錄送給 Provider 的 user prompt（包含問題、檢索 Chunk、SQL dependencies、procedure calls 及程式碼）；System Instructions 不記錄。預設為 `false`。日誌可能含完整 PCK 程式碼，勿對未核准的公司程式碼開啟或分享。設為 `false` 並重新啟動即可關閉。變更位於 `AssistantPromptLogger.java`、各 Provider adapter、`AssistantModelConfiguration.java`、`application.yml` 與 `README.md`。這項變更尚未執行 Maven 測試。

## 資料與安全注意

- `evaluation/lineage_ground_truth.json` 與 `evaluation/test_questions.json` 是人工評估資料，只能在使用者要求的離線評分階段使用；不可作為 Retrieval 或 LLM 提示輸入。
- 公司內部 PCK 尚未授權使用雲端模型前，只能用虛構 PCK 或公司核准的內部模型測試。
- 不要把 API Key、OAuth Token、裝置登入碼寫入檔案、日誌或聊天。
- 使用 Copilot CLI headless server 時，只綁定本機 loopback，勿對公網開放。

## 可供使用者自行啟動的 Copilot 測試設定

需先確認 Copilot 帳號用量額度可用。第一個 PowerShell 視窗：

```powershell
copilot --headless --port 4321
```

第二個 PowerShell 視窗：

```powershell
$env:LINEAGE_ASSISTANT_PROVIDER = "copilot"
$env:COPILOT_CLI_URL = "http://localhost:4321"
$env:COPILOT_CHAT_MODEL = "auto"
$env:COPILOT_TIMEOUT_SECONDS = "90"
$env:LINEAGE_PACKAGES_PATH = (Resolve-Path .\packages).Path
cd backend
mvn spring-boot:run
```

再呼叫 `POST http://localhost:8080/api/assistant/ask`，例如 body：

```json
{
  "question": "EFFECTIVE_DATE 的來源與轉換規則是什麼？",
  "column": "TGT_POLICY.EFFECTIVE_DATE"
}
```

## 交接給 ChatGPT 網頁版的方式

這份檔案是專案摘要，不是完整聊天逐字稿。請在 ChatGPT 網頁版開啟新對話，將 `CHATGPT_HANDOFF.md` 拖入對話視窗，或貼上檔案內容，再提出下一個需求。不要附上 API Key、Token 或登入碼。
