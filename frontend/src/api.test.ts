import { afterEach, describe, expect, it, vi } from 'vitest'
import { askAssistant, getChunk, getProvider } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('lineage API client', () => {
  it('posts question and optional column to the assistant endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      answer: '欄位追蹤結果', sources: [], limitations: [],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await askAssistant('來源欄位是什麼？', '')

    expect(fetchMock).toHaveBeenCalledWith('/api/assistant/ask', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ question: '來源欄位是什麼？', column: null }),
    }))
  })

  it('uses backend provider status and full chunk detail endpoints', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ provider: 'gemini', model: 'test-model', ready: true }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ chunkId: 'chunk/1', content: 'SELECT 1;' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await getProvider()).toMatchObject({ provider: 'gemini', ready: true })
    await getChunk('chunk/1')

    expect(fetchMock).toHaveBeenLastCalledWith('/api/chunks/chunk%2F1', expect.any(Object))
  })

  it('shows backend problem detail for API failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ detail: '請設定 GEMINI_API_KEY' }),
      { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
    )))

    await expect(getProvider()).rejects.toThrow('請設定 GEMINI_API_KEY')
  })
})
