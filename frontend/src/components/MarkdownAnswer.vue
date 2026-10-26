<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps<{ content: string }>()

const safeHtml = computed(() => DOMPurify.sanitize(marked.parse(props.content) as string))
</script>

<template>
  <div class="markdown-answer" v-html="safeHtml"></div>
</template>
