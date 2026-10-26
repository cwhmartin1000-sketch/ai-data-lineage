import type { AskResponse, PolicyChunk, ProviderInfo } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('無法連線至後端。請確認 Spring Boot 已啟動，並檢查 API 位址設定。')
  }

  if (!response.ok) {
    let detail = `後端請求失敗（HTTP ${response.status}）`
    try {
      const problem = (await response.json()) as { detail?: string; message?: string }
      detail = problem.detail || problem.message || detail
    } catch {
      // Keep the status-based message when the response is not JSON.
    }
    throw new Error(localizeBackendError(detail))
  }

  return (await response.json()) as T
}

function localizeBackendError(detail: string): string {
  if (detail.startsWith('No assistant model is configured')) {
    return '後端尚未啟用 AI 模型。請設定 LINEAGE_ASSISTANT_PROVIDER 與該模型所需的後端環境變數，再重新啟動服務。'
  }
  const missingSetting = detail.match(/Assistant provider '([^']+)' is selected but required setting (.+) is missing\./)
  if (missingSetting) {
    return `後端選用的 ${missingSetting[1]} Provider 缺少必要設定：${missingSetting[2]}。設定後請重新啟動服務。`
  }
  if (detail.startsWith('The selected assistant provider could not complete the request')) {
    return '模型服務暫時無法完成請求。請確認後端的憑證、端點與模型設定。'
  }
  return detail
}

export function getProvider(): Promise<ProviderInfo> {
  return request<ProviderInfo>('/api/assistant/provider')
}

export function askAssistant(question: string, column: string): Promise<AskResponse> {
  return request<AskResponse>('/api/assistant/ask', {
    method: 'POST',
    body: JSON.stringify({ question, column: column.trim() || null }),
  })
}

export function getChunk(chunkId: string): Promise<PolicyChunk> {
  return request<PolicyChunk>(`/api/chunks/${encodeURIComponent(chunkId)}`)
}
