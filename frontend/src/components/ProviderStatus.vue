<script setup lang="ts">
import type { ProviderInfo } from '../types'

defineProps<{
  provider: ProviderInfo | null
  loading: boolean
  error: string
}>()

</script>

<template>
  <div class="provider-control" :title="error || 'Provider 由後端環境變數設定，前端無法切換。'">
    <span class="provider-control__caption">目前模型</span>
    <span class="provider-select-wrap">
      <span class="provider-orb" :class="{ 'provider-orb--ready': provider?.ready }"></span>
      <select aria-label="後端設定的模型 Provider" :value="provider?.provider ?? 'none'" disabled>
        <option value="none">尚未設定</option>
        <option value="openai">OpenAI</option>
        <option value="gemini">Gemini</option>
        <option value="ollama">Ollama（本機）</option>
      </select>
      <span class="select-chevron">⌄</span>
    </span>
    <span class="provider-model" v-if="loading">同步中</span>
    <span class="provider-model" v-else-if="provider?.model">{{ provider.model }}</span>
    <span class="provider-model provider-model--error" v-else>{{ error ? '狀態讀取失敗' : (provider?.ready ? '' : '未就緒') }}</span>
    <span class="provider-lock" aria-label="Provider 由後端管理" title="Provider 由後端環境變數決定">後端設定</span>
  </div>
</template>
