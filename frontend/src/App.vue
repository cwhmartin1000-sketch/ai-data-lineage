<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { analyzeField, askAssistant, getChunk, getProvider } from './api'
import MarkdownAnswer from './components/MarkdownAnswer.vue'
import ProviderStatus from './components/ProviderStatus.vue'
import SidebarPanel from './components/SidebarPanel.vue'
import type { AssistantSource, Conversation, ConversationMessage, PolicyChunk, ProviderInfo } from './types'

const STORAGE_KEY = 'lineage-ai-conversations-v1'
const suggestions = [
  { icon: '◎', title: '追蹤欄位來源', text: 'EFFECTIVE_DATE 的來源欄位與轉換規則是什麼？', column: 'EFFECTIVE_DATE' },
  { icon: '⇄', title: '了解轉換邏輯', text: 'PENDING 保單的生效日是如何計算的？', column: 'CALCULATED_EFFECTIVE_DATE' },
  { icon: '⌘', title: '探索程式證據', text: '哪些 Procedure 會讀取 POLICY_START_DT？', column: 'POLICY_START_DT' },
]

const conversations = ref<Conversation[]>(readConversations())
const activeId = ref<string | null>(conversations.value[0]?.id ?? null)
const activeConversation = computed(() => conversations.value.find((item) => item.id === activeId.value) ?? null)
const messages = computed(() => activeConversation.value?.messages ?? [])

const analysisMode = ref('field')
const question = ref('')
const column = ref('')
const busy = ref(false)
const provider = ref<ProviderInfo | null>(null)
const providerLoading = ref(true)
const providerError = ref('')
const mobileSidebarOpen = ref(false)
const selectedSource = ref<AssistantSource | null>(null)
const selectedChunk = ref<PolicyChunk | null>(null)
const sourceLoading = ref(false)
const sourceError = ref('')
const messageList = ref<HTMLElement | null>(null)

const chunkLines = computed(() => {
  if (!selectedChunk.value) return []
  const lines = selectedChunk.value.content.replace(/\r\n/g, '\n').split('\n')
  if (lines.at(-1) === '') lines.pop()
  return lines.map((text, offset) => ({ lineNumber: selectedChunk.value!.startLine + offset, text }))
})
const matchedLineNumbers = computed(() => new Set(selectedSource.value?.matchedLines.map((line) => line.lineNumber) ?? []))

function readConversations(): Conversation[] {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved ? (JSON.parse(saved) as Conversation[]) : []
  } catch {
    return []
  }
}

function saveConversations() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations.value))
  } catch {
    // Keep the current conversation usable when browser storage is unavailable.
  }
}

function newConversation() {
  activeId.value = null
  question.value = ''
  column.value = ''
  selectedSource.value = null
  selectedChunk.value = null
  sourceError.value = ''
}

function selectConversation(id: string) {
  activeId.value = id
  selectedSource.value = null
  selectedChunk.value = null
  sourceError.value = ''
}

function deleteConversation(id: string) {
  if (!conversations.value.some((item) => item.id === id)) return
  conversations.value = conversations.value.filter((item) => item.id !== id)
  if (activeId.value === id) {
    const next = conversations.value[0]
    if (next) {
      selectConversation(next.id)
      question.value = ''
      column.value = ''
    } else {
      newConversation()
    }
  }
  saveConversations()
}

function createConversation(title: string): Conversation {
  const conversation: Conversation = {
    id: crypto.randomUUID(),
    title: title.length > 36 ? `${title.slice(0, 36)}…` : title,
    updatedAt: Date.now(),
    messages: [],
  }
  conversations.value.unshift(conversation)
  activeId.value = conversation.id
  return conversation
}

function pickSuggestion(item: (typeof suggestions)[number]) {
  question.value = item.text
  column.value = item.column
  document.querySelector<HTMLTextAreaElement>('.composer-input')?.focus()
}

async function submitQuestion() {
  const text = question.value.trim()
  if (!text || busy.value || (analysisMode.value === 'field' && !column.value.trim())) return
  const fieldMode = analysisMode.value === 'field'

  const active = activeConversation.value ?? createConversation(text)
  const userMessage: ConversationMessage = {
    id: crypto.randomUUID(), role: 'user', content: text, column: column.value.trim() || undefined,
  }
  const assistantMessage: ConversationMessage = {
    id: crypto.randomUUID(), role: 'assistant', content: '', pending: true,
  }
  active.messages.push(userMessage, assistantMessage)
  active.updatedAt = Date.now()
  conversations.value.sort((a, b) => b.updatedAt - a.updatedAt)
  question.value = ''
  busy.value = true
  saveConversations()
  await scrollMessagesToBottom()

  try {
    const response = await (fieldMode ? analyzeField : askAssistant)(text, userMessage.column ?? '')
    assistantMessage.content = response.answer
    assistantMessage.sources = response.sources
    assistantMessage.limitations = response.limitations
  } catch (error) {
    assistantMessage.content = error instanceof Error ? error.message : '發生未知錯誤，請稍後再試。'
    assistantMessage.error = true
  } finally {
    assistantMessage.pending = false
    active.updatedAt = Date.now()
    busy.value = false
    saveConversations()
    await scrollMessagesToBottom()
  }
}

async function openSource(source: AssistantSource) {
  selectedSource.value = source
  selectedChunk.value = null
  sourceError.value = ''
  sourceLoading.value = true
  try {
    const chunk = await getChunk(source.chunkId)
    if (selectedSource.value?.chunkId === source.chunkId) selectedChunk.value = chunk
  } catch (error) {
    if (selectedSource.value?.chunkId === source.chunkId) {
      sourceError.value = error instanceof Error ? error.message : '無法載入程式碼來源。'
    }
  } finally {
    if (selectedSource.value?.chunkId === source.chunkId) sourceLoading.value = false
  }
}

function retrySelectedSource() {
  if (selectedSource.value) void openSource(selectedSource.value)
}

async function scrollMessagesToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void submitQuestion()
  }
}

onMounted(async () => {
  providerLoading.value = true
  try {
    provider.value = await getProvider()
  } catch (error) {
    providerError.value = error instanceof Error ? error.message : '無法讀取後端模型設定。'
  } finally {
    providerLoading.value = false
  }
})

watch(activeId, () => void scrollMessagesToBottom())
</script>

<template>
  <div class="app-shell">
    <div v-if="mobileSidebarOpen" class="mobile-scrim" @click="mobileSidebarOpen = false"></div>
    <SidebarPanel
      :conversations="conversations"
      :active-id="activeId"
      :mobile-open="mobileSidebarOpen"
      @new-conversation="newConversation"
      @select-conversation="selectConversation"
      @delete-conversation="deleteConversation"
      @close="mobileSidebarOpen = false"
    />

    <main id="assistant" class="main-panel">
      <header class="topbar">
        <div class="topbar-left">
          <button class="icon-button mobile-menu" aria-label="開啟導覽" @click="mobileSidebarOpen = true">☰</button>
          <div class="breadcrumbs"><span>工作區</span><span class="breadcrumb-divider">/</span><strong>資料轉換助理</strong></div>
        </div>
        <ProviderStatus :provider="provider" :loading="providerLoading" :error="providerError" />
      </header>

      <div v-if="providerError" class="provider-alert">
        <span class="alert-icon">!</span>
        <span>無法確認後端 Provider：{{ providerError }}</span>
      </div>

      <div class="workspace-grid">
        <section class="chat-workspace" aria-label="AI 問答">
          <div ref="messageList" class="messages-scroll">
            <div v-if="messages.length === 0" class="welcome-state">
              <div class="welcome-kicker"><span></span> DATA TRANSFORMATION</div>
              <div class="welcome-emblem"><span class="emblem-ring emblem-ring--outer"></span><span class="emblem-ring emblem-ring--inner"></span><span class="emblem-core">✳</span></div>
              <h1>讓每個欄位的來處，<br /><span>都有跡可循。</span></h1>
              <p class="welcome-description">詢問 PL/SQL 欄位血緣與轉換規則，從實際程式碼證據開始追溯。</p>

              <div class="suggestion-grid">
                <button v-for="item in suggestions" :key="item.title" class="suggestion-card" @click="pickSuggestion(item)">
                  <span class="suggestion-icon">{{ item.icon }}</span>
                  <span class="suggestion-title">{{ item.title }}</span>
                  <span class="suggestion-text">{{ item.text }}</span>
                  <span class="suggestion-arrow">↗</span>
                </button>
              </div>
            </div>

            <div v-else class="message-list">
              <div v-for="message in messages" :key="message.id" class="message-row" :class="`message-row--${message.role}`">
                <template v-if="message.role === 'user'">
                  <div class="user-message-wrap">
                    <div class="user-message-meta">
                      <span>{{ message.column ? `欄位 · ${message.column}` : '一般問題' }}</span>
                      <span class="meta-dot">·</span>
                      <span>你</span>
                    </div>
                    <div class="user-message">{{ message.content }}</div>
                  </div>
                  <div class="avatar avatar--user">你</div>
                </template>

                <template v-else>
                  <div class="avatar avatar--assistant"><span>✳</span></div>
                  <div class="assistant-message-wrap">
                    <div class="assistant-message-meta">
                      <span class="assistant-name">資料轉換 AI</span>
                      <span class="verified-pill"><span>✓</span> 程式碼證據</span>
                    </div>
                    <div v-if="message.pending" class="thinking-card" aria-live="polite">
                      <span class="thinking-dots"><i></i><i></i><i></i></span>
                      <span>正在檢索程式碼並分析；欄位規格可能需要分批處理…</span>
                    </div>
                    <div v-else class="answer-card" :class="{ 'answer-card--error': message.error }">
                      <MarkdownAnswer :content="message.content" />
                    </div>

                    <div v-if="message.sources?.length" class="sources-section">
                      <div class="section-heading"><span class="heading-mark">▤</span><span>參考來源</span><span class="section-count">{{ message.sources.length }}</span><span class="heading-line"></span></div>
                      <div class="source-list">
                        <button
                          v-for="source in message.sources"
                          :key="source.citationId"
                          class="source-card"
                          :class="{ 'source-card--selected': selectedSource?.chunkId === source.chunkId }"
                          @click="openSource(source)"
                        >
                          <span class="source-ref">{{ source.citationId }}</span>
                          <span class="source-main">
                            <span class="source-file">{{ source.fileName }}</span>
                            <span class="source-context">{{ source.packageName }} <span>›</span> {{ source.procedureName }}</span>
                          </span>
                          <span class="source-lines">L{{ source.startLine }}–{{ source.endLine }}</span>
                          <span class="source-open">↗</span>
                        </button>
                      </div>
                    </div>

                    <div v-if="message.limitations?.length" class="limitations-box">
                      <span class="limitations-icon">ⓘ</span>
                      <div><strong>分析範圍說明</strong><p v-for="item in message.limitations" :key="item">{{ item }}</p></div>
                    </div>
                    <div v-if="!message.pending && !message.error" class="answer-actions">
                      <span>回答依據檢索到的程式碼產生</span>
                      <span class="answer-action-icons"><span title="已複製來源">▤</span><span title="回答完成">✓</span></span>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <div class="composer-area">
            <form class="composer" @submit.prevent="submitQuestion">
              <div class="composer-context">
                <label class="analysis-mode-label">分析方式
                  <select v-model="analysisMode" :disabled="busy" aria-label="分析方式">
                    <option value="field">欄位規格：情境、條件與檢核 SQL</option>
                    <option value="question">一般問答</option>
                  </select>
                </label>
                <label class="column-field">
                  <span class="column-field-icon">⌗</span>
                  <span class="sr-only">欄位名稱</span>
                  <input v-model="column" type="text" :placeholder="analysisMode === 'field' ? '欄位名稱（必填，例如 PODT.POLICY_NO）' : '欄位名稱（選填）'" :required="analysisMode === 'field'" autocomplete="off" />
                  <span class="optional-label">{{ analysisMode === 'field' ? '必填' : '選填' }}</span>
                </label>
                <span class="context-hint"><span class="context-dot"></span> 以原始 PL/SQL 作為回答證據</span>
              </div>
              <div class="composer-entry">
                <textarea
                  v-model="question"
                  class="composer-input"
                  rows="2"
                  placeholder="想了解哪個欄位的資料來源或轉換邏輯？"
                  aria-label="輸入問題"
                  @keydown="onComposerKeydown"
                ></textarea>
                <div class="composer-bottom">
                  <div class="composer-tools"><span class="composer-secure">◈ <span>依後端設定的來源分析</span></span><span class="tool-separator"></span><span class="keyboard-hint">⌘ Enter 傳送</span></div>
                  <button class="send-button" type="submit" :disabled="!question.trim() || busy || (analysisMode === 'field' && !column.trim())" aria-label="傳送問題">
                    <span v-if="busy" class="send-spinner"></span><span v-else>↑</span>
                  </button>
                </div>
              </div>
            </form>
            <p class="composer-disclaimer">AI 回答可能不完整，請點選來源檢視原始程式碼。</p>
          </div>
        </section>

        <aside id="evidence" class="evidence-panel" aria-label="PL/SQL 程式碼證據">
          <div class="evidence-header">
            <div class="evidence-title-row"><div class="evidence-title-icon">⌘</div><div><h2>程式碼證據</h2><p>PL/SQL SOURCE INSPECTOR</p></div></div>
            <button class="icon-button evidence-menu" aria-label="證據面板選項">···</button>
          </div>

          <div v-if="!selectedSource" class="evidence-placeholder">
            <div class="placeholder-art">
              <div class="art-window"><div class="art-window-top"><i></i><i></i><i></i><span>package.pkb</span></div><div class="art-code"><span>01</span><i></i><span>02</span><b></b><span>03</span><i></i><span>04</span><em></em><span>05</span><i></i></div></div>
              <div class="art-spark art-spark--one">✦</div><div class="art-spark art-spark--two">✧</div>
            </div>
            <h3>讓證據說明脈絡</h3>
            <p>點擊 AI 回答中的來源卡片，<br />在這裡檢視完整 PL/SQL 程式碼。</p>
            <div class="placeholder-tip"><span>⌁</span> 命中行會以淡金色標記</div>
          </div>

          <template v-else>
            <div class="source-detail-head">
              <div class="file-type-icon">PK</div>
              <div class="file-detail"><div class="file-name" :title="selectedSource.fileName">{{ selectedSource.fileName }}</div><div class="file-meta">{{ selectedSource.packageName }}</div></div>
              <span class="file-extension">.{{ selectedSource.fileName.split('.').at(-1) }}</span>
            </div>
            <div class="routine-card">
              <div class="routine-label">PROCEDURE / FUNCTION</div>
              <div class="routine-name"><span>ƒ</span>{{ selectedSource.procedureName }}</div>
            </div>
            <div class="range-bar"><span class="range-live-dot"></span><span>來源範圍</span><strong>第 {{ selectedSource.startLine }}–{{ selectedSource.endLine }} 行</strong></div>
            <div class="code-toolbar"><span>原始程式碼</span><span class="code-language"><i></i> PL/SQL</span></div>

            <div v-if="sourceLoading" class="code-state"><span class="large-spinner"></span><span>載入完整 Chunk…</span></div>
            <div v-else-if="sourceError" class="code-state code-state--error"><span class="code-error-mark">!</span><strong>載入失敗</strong><span>{{ sourceError }}</span><button @click="retrySelectedSource">重試</button></div>
            <div v-else-if="selectedChunk" class="code-viewer" tabindex="0" aria-label="程式碼內容">
              <div v-for="line in chunkLines" :key="line.lineNumber" class="code-line" :class="{ 'code-line--matched': matchedLineNumbers.has(line.lineNumber) }">
                <span class="line-number">{{ line.lineNumber }}</span>
                <span class="line-match-dot"></span>
                <code>{{ line.text || ' ' }}</code>
              </div>
            </div>

            <div class="evidence-legend"><span><i></i> AI 回答引用行</span><span>完整來源 · {{ selectedSource.startLine }}–{{ selectedSource.endLine }}</span></div>
          </template>
          <div class="evidence-footer"><span class="footer-shield">◇</span><span>來源直接取自後端 Package 原始檔</span><span class="evidence-footer-check">✓</span></div>
        </aside>
      </div>
    </main>
  </div>
</template>
