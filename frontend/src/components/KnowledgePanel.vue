<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useKnowledgeStore } from '../stores/knowledge'

const knowledge = useKnowledgeStore()
const fileInput = ref<HTMLInputElement | null>(null)
let polling = false

onMounted(() => {
  refresh()
})

onBeforeUnmount(() => {
  polling = false
})

async function refresh() {
  try {
    await knowledge.loadDocuments()
    if (knowledge.processing && !polling) {
      void poll().catch(() => undefined)
    }
  } catch {
    // The store exposes the user-visible error.
  }
}

async function poll() {
  polling = true
  try {
    while (polling && knowledge.processing) {
      await knowledge.pollProcessing({ intervalMs: 1000, maxAttempts: 1 })
    }
  } finally {
    polling = false
  }
}

function chooseFile() {
  fileInput.value?.click()
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    await knowledge.uploadDocument(file, { intervalMs: 1000, maxAttempts: 30 })
    if (knowledge.processing && !polling) {
      void poll().catch(() => undefined)
    }
  } catch {
    // The store exposes the user-visible error.
  }
}

async function removeDocument(documentId: string, filename: string) {
  if (!window.confirm(`确认删除文档“${filename}”？`)) return
  try {
    await knowledge.deleteDocument(documentId)
  } catch {
    // The store exposes the user-visible error.
  }
}

function statusLabel(status: string) {
  return status === 'PROCESSING' ? '解析中' : status === 'READY' ? '就绪' : '失败'
}

function sizeLabel(size: number | null) {
  if (size === null || size <= 0) return '大小未知'
  if (size < 1024) return `${size} B`
  return `${(size / 1024).toFixed(1)} KB`
}

function createdAtLabel(createdAt: string) {
  return createdAt.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <section class="knowledge-panel" aria-label="知识库">
    <div class="panel-row knowledge-panel__header">
      <div>
        <strong>个人知识库</strong>
        <p>文档会异步解析、切分并建立检索索引。</p>
      </div>
      <el-button type="primary" size="small" :loading="knowledge.uploading" @click="chooseFile">
        上传文档
      </el-button>
      <input ref="fileInput" class="knowledge-panel__file" type="file" accept=".txt,.md,.pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg" @change="upload" />
    </div>

    <div v-if="knowledge.error" class="panel-error">{{ knowledge.error }}</div>
    <div v-if="knowledge.loading && !knowledge.documents.length" class="panel-muted">正在加载文档…</div>
    <div v-else-if="!knowledge.documents.length" class="panel-muted">暂无文档，上传后开始构建知识库。</div>

    <div v-else class="knowledge-document-list">
      <article v-for="document in knowledge.documents" :key="document.id" class="knowledge-document-item">
        <div class="knowledge-document-item__title">
          <strong :title="document.originalFilename">{{ document.originalFilename }}</strong>
          <span class="knowledge-document-item__actions">
            <el-tag size="small" :type="document.status === 'READY' ? 'success' : document.status === 'FAILED' ? 'danger' : 'warning'">
              {{ document.status }} · {{ statusLabel(document.status) }}
            </el-tag>
            <el-button
              :data-testid="`delete-document-${document.id}`"
              size="small"
              type="danger"
              text
              :loading="knowledge.deletingId === document.id"
              :disabled="Boolean(knowledge.deletingId)"
              @click="removeDocument(document.id, document.originalFilename)"
            >
              删除
            </el-button>
          </span>
        </div>
        <div class="knowledge-document-item__meta">
          <span>上传时间 {{ createdAtLabel(document.createdAt) }}</span>
          <span>{{ sizeLabel(document.sizeBytes) }}</span>
          <span>父文档 {{ document.parentCount }}</span>
          <span>子文档 {{ document.childCount }}</span>
        </div>
        <p v-if="document.errorMessage" class="panel-error">{{ document.errorMessage }}</p>
      </article>
    </div>
  </section>
</template>
