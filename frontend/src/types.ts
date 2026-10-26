export type ProviderName = 'none' | 'openai' | 'gemini' | 'ollama'

export interface ProviderInfo {
  provider: ProviderName
  model: string | null
  ready: boolean
}

export interface MatchedLine {
  lineNumber: number
  text: string
}

export interface AssistantSource {
  citationId: string
  chunkId: string
  packageName: string
  procedureName: string
  fileName: string
  startLine: number
  endLine: number
  matchedLines: MatchedLine[]
}

export interface AskResponse {
  answer: string
  sources: AssistantSource[]
  limitations: string[]
}

export interface PolicyChunk {
  chunkId: string
  packageName: string
  procedureName: string
  fileName: string
  startLine: number
  endLine: number
  content: string
}

export interface ConversationMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  column?: string
  sources?: AssistantSource[]
  limitations?: string[]
  pending?: boolean
  error?: boolean
}

export interface Conversation {
  id: string
  title: string
  updatedAt: number
  messages: ConversationMessage[]
}
