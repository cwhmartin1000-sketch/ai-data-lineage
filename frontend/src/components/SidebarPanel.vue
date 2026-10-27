<script setup lang="ts">
import type { Conversation } from '../types'

defineProps<{
  conversations: Conversation[]
  activeId: string | null
  mobileOpen: boolean
}>()

const emit = defineEmits<{
  newConversation: []
  selectConversation: [id: string]
  deleteConversation: [id: string]
  close: []
}>()

function dateLabel(timestamp: number): string {
  const date = new Date(timestamp)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) return '今天'
  return new Intl.DateTimeFormat('zh-TW', { month: 'numeric', day: 'numeric' }).format(date)
}

function navigateTo(sectionId: string) {
  document.getElementById(sectionId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  emit('close')
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--open': mobileOpen }">
    <div class="sidebar-brand">
      <div class="brand-mark"><span></span><span></span><span></span></div>
      <div>
        <div class="brand-name">TRANSFORM<span>AI</span></div>
        <div class="brand-caption">DATA TRANSFORMATION</div>
      </div>
      <button class="icon-button sidebar-close" aria-label="關閉導覽" @click="emit('close')">×</button>
    </div>

    <button class="new-chat-button" @click="emit('newConversation'); emit('close')">
      <span class="new-chat-icon">＋</span>
      <span>新增對話</span>
      <span class="new-chat-shortcut">⌘ K</span>
    </button>

    <div class="sidebar-section-label">工作區</div>
    <nav class="workspace-nav" aria-label="功能導覽">
      <a class="workspace-link workspace-link--active" href="#assistant" @click.prevent="navigateTo('assistant')">
        <span class="nav-icon nav-icon--spark">✳</span><span>資料轉換助理</span>
        <span class="nav-active-dot"></span>
      </a>
      <a class="workspace-link" href="#evidence" @click.prevent="navigateTo('evidence')">
        <span class="nav-icon">⌘</span><span>程式碼證據</span>
        <span class="nav-count">即時</span>
      </a>
    </nav>

    <div class="history-heading">
      <span class="sidebar-section-label">最近對話</span>
      <span class="history-total">{{ conversations.length }}</span>
    </div>
    <div class="conversation-list" v-if="conversations.length">
      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{ 'conversation-item--active': conversation.id === activeId }"
      >
        <button
          class="conversation-select"
          :aria-current="conversation.id === activeId ? 'true' : undefined"
          @click="emit('selectConversation', conversation.id); emit('close')"
        >
          <span class="conversation-icon">◷</span>
          <span class="conversation-copy">
            <span class="conversation-title">{{ conversation.title }}</span>
            <span class="conversation-date">{{ dateLabel(conversation.updatedAt) }}</span>
          </span>
        </button>
        <button
          class="conversation-delete"
          :aria-label="'刪除對話：' + conversation.title"
          :title="'刪除對話：' + conversation.title"
          @click="emit('deleteConversation', conversation.id)"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7" />
          </svg>
        </button>
      </div>
    </div>
    <div class="history-empty" v-else>
      <span class="history-empty-icon">✧</span>
      <span>你的對話會保存在這裡</span>
    </div>

    <div class="sidebar-footer">
      <div class="footer-orbit"><span></span></div>
      <div class="footer-copy">
        <span class="footer-title">安全的測試環境</span>
        <span class="footer-caption">僅使用虛構 PL/SQL 資料</span>
      </div>
      <span class="footer-status-dot"></span>
    </div>
  </aside>
</template>
