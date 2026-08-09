<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ChatTranscript from '../components/ChatTranscript.vue'
import Composer from '../components/Composer.vue'
import SessionSidebar from '../components/SessionSidebar.vue'
import SkillPanel from '../components/SkillPanel.vue'
import SkillReviewPanel from '../components/SkillReviewPanel.vue'
import KnowledgePanel from '../components/KnowledgePanel.vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useSessionsStore } from '../stores/sessions'
import type { SessionMode } from '../api/chat'

const auth = useAuthStore()
const chat = useChatStore()
const router = useRouter()
const sessions = useSessionsStore()

const SESSION_SIDEBAR_WIDTH_KEY = 'myagent.chat.sessionSidebarWidth'
const ASSISTANT_PANEL_WIDTH_KEY = 'myagent.chat.assistantPanelWidth'
const SESSION_SIDEBAR_DEFAULT_WIDTH = 280
const ASSISTANT_PANEL_DEFAULT_WIDTH = 360
const SESSION_SIDEBAR_MIN_WIDTH = 220
const SESSION_SIDEBAR_MAX_WIDTH = 420
const ASSISTANT_PANEL_MIN_WIDTH = 280
const ASSISTANT_PANEL_MAX_WIDTH = 500
const MAIN_MIN_WIDTH = 420
const RESIZER_WIDTH = 8

type Sidebar = 'session' | 'assistant'

const workspace = ref<HTMLElement>()
const activeResizer = ref<Sidebar>()
const activePointerId = ref<number>()
const activeResizerElement = ref<HTMLElement>()
const sessionSidebarWidth = ref(
  readSavedWidth(SESSION_SIDEBAR_WIDTH_KEY, SESSION_SIDEBAR_DEFAULT_WIDTH, SESSION_SIDEBAR_MIN_WIDTH, SESSION_SIDEBAR_MAX_WIDTH)
)
const assistantPanelWidth = ref(
  readSavedWidth(ASSISTANT_PANEL_WIDTH_KEY, ASSISTANT_PANEL_DEFAULT_WIDTH, ASSISTANT_PANEL_MIN_WIDTH, ASSISTANT_PANEL_MAX_WIDTH)
)
const workspaceStyle = computed(() => ({
  '--session-sidebar-width': `${sessionSidebarWidth.value}px`,
  '--assistant-panel-width': `${assistantPanelWidth.value}px`
}))

const currentSessionId = computed(() => sessions.currentSessionId)
const currentMessages = computed(() =>
  currentSessionId.value ? chat.messages(currentSessionId.value) : []
)
const isSending = computed(() => chat.loadingSessionId === currentSessionId.value)
const currentMode = computed<SessionMode>(() => sessions.currentSession?.mode ?? 'NORMAL')
const currentModeLabel = computed(() => currentMode.value === 'KNOWLEDGE' ? '知识库问答' : '普通对话')
const modeDialogVisible = ref(false)
const pendingMode = ref<SessionMode | ''>('')

watch(
  currentSessionId,
  (sessionId) => {
    if (sessionId) {
      chat.useSession(sessionId)
      chat.loadMessages(sessionId).catch(() => {
        // The chat store exposes the load error in the page-level error banner.
      })
    }
  },
  { immediate: true }
)

onMounted(async () => {
  clampSavedSidebarWidths()
  window.addEventListener('resize', clampSavedSidebarWidths)
  await sessions.loadSessions()
})

onBeforeUnmount(() => {
  finishResize(undefined, false)
  window.removeEventListener('resize', clampSavedSidebarWidths)
})

function readSavedWidth(key: string, defaultWidth: number, minWidth: number, maxWidth: number) {
  const rawWidth = window.localStorage.getItem(key)
  const width = rawWidth === null || rawWidth.trim() === '' ? Number.NaN : Number(rawWidth)

  if (!Number.isFinite(width) || width < minWidth || width > maxWidth) {
    return defaultWidth
  }

  return width
}

function isDesktopLayout() {
  return window.innerWidth > 1180
}

function boundsFor(sidebar: Sidebar) {
  return sidebar === 'session'
    ? [SESSION_SIDEBAR_MIN_WIDTH, SESSION_SIDEBAR_MAX_WIDTH]
    : [ASSISTANT_PANEL_MIN_WIDTH, ASSISTANT_PANEL_MAX_WIDTH]
}

function constrainSidebarWidth(sidebar: Sidebar, width: number, workspaceWidth: number) {
  const [minWidth, maxWidth] = boundsFor(sidebar)
  const oppositeWidth = sidebar === 'session' ? assistantPanelWidth.value : sessionSidebarWidth.value
  const maxWidthWithMain = workspaceWidth - (RESIZER_WIDTH * 2) - MAIN_MIN_WIDTH - oppositeWidth
  const constrainedMaxWidth = Math.max(minWidth, Math.min(maxWidth, maxWidthWithMain))

  return Math.min(Math.max(width, minWidth), constrainedMaxWidth)
}

function setSidebarWidth(sidebar: Sidebar, width: number, workspaceWidth: number) {
  const constrainedWidth = constrainSidebarWidth(sidebar, width, workspaceWidth)

  if (sidebar === 'session') {
    sessionSidebarWidth.value = constrainedWidth
  } else {
    assistantPanelWidth.value = constrainedWidth
  }
}

function clampSavedSidebarWidths() {
  if (!isDesktopLayout() || !workspace.value) {
    return
  }

  const workspaceWidth = workspace.value.getBoundingClientRect().width
  if (workspaceWidth <= 0) {
    return
  }

  setSidebarWidth('session', sessionSidebarWidth.value, workspaceWidth)
  setSidebarWidth('assistant', assistantPanelWidth.value, workspaceWidth)
}

function startResize(sidebar: Sidebar, event: PointerEvent) {
  if (!isDesktopLayout() || activePointerId.value !== undefined) {
    return
  }

  event.preventDefault()
  const resizer = event.currentTarget as HTMLElement
  activeResizer.value = sidebar
  activePointerId.value = event.pointerId
  activeResizerElement.value = resizer
  resizer.setPointerCapture?.(event.pointerId)
  document.body.classList.add('chat-resizing')
  window.addEventListener('pointermove', handleResize)
  window.addEventListener('pointerup', finishResize)
  window.addEventListener('pointercancel', finishResize)
}

function handleResize(event: PointerEvent) {
  if (
    !activeResizer.value
    || !workspace.value
    || !isDesktopLayout()
    || event.pointerId !== activePointerId.value
  ) {
    return
  }

  const workspaceBounds = workspace.value.getBoundingClientRect()
  const width = activeResizer.value === 'session'
    ? event.clientX - workspaceBounds.left
    : workspaceBounds.right - event.clientX

  setSidebarWidth(activeResizer.value, width, workspaceBounds.width)
}

function finishResize(event?: PointerEvent, persist = true) {
  if (event && event.pointerId !== activePointerId.value) {
    return
  }

  const sidebar = activeResizer.value
  const pointerId = activePointerId.value
  const resizer = activeResizerElement.value
  activeResizer.value = undefined
  activePointerId.value = undefined
  activeResizerElement.value = undefined
  document.body.classList.remove('chat-resizing')
  window.removeEventListener('pointermove', handleResize)
  window.removeEventListener('pointerup', finishResize)
  window.removeEventListener('pointercancel', finishResize)

  if (pointerId !== undefined && resizer) {
    try {
      resizer.releasePointerCapture?.(pointerId)
    } catch {
      // The pointer may already have been released by the browser.
    }
  }

  if (!persist) {
    return
  }

  if (sidebar === 'session') {
    window.localStorage.setItem(SESSION_SIDEBAR_WIDTH_KEY, String(sessionSidebarWidth.value))
  } else if (sidebar === 'assistant') {
    window.localStorage.setItem(ASSISTANT_PANEL_WIDTH_KEY, String(assistantPanelWidth.value))
  }
}

async function createSession() {
  pendingMode.value = ''
  modeDialogVisible.value = true
}

async function confirmCreateSession() {
  if (!pendingMode.value) return
  const session = await sessions.createSession(undefined, pendingMode.value)
  modeDialogVisible.value = false
  pendingMode.value = ''
  chat.useSession(session.id)
}

function selectSession(sessionId: string) {
  sessions.selectSession(sessionId)
  chat.useSession(sessionId)
}

async function deleteSession(sessionId: string) {
  if (sessions.deletingSessionId) {
    return
  }

  chat.abortSession(sessionId)

  try {
    await sessions.deleteSession(sessionId)
    chat.clearSession(sessionId)
  } catch {
    // The sessions store exposes the server error while preserving the session.
  }
}

async function renameSession(sessionId: string, title: string) {
  await sessions.renameSession(sessionId, title)
}

async function sendMessage(message: string) {
  let sessionId = currentSessionId.value

  if (!sessionId) {
    const session = await sessions.createSession()
    sessionId = session.id
    chat.useSession(sessionId)
  }

  await chat.sendMessage(sessionId, message)
}

async function logout() {
  auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <main class="app-page chat-page">
    <header class="chat-topbar">
      <div class="chat-brand">
        <strong>MyAgent</strong>
      </div>
      <div class="chat-topbar__actions">
        <span class="chat-topbar__username">{{ auth.user?.username }}</span>
        <el-button @click="logout">退出登录</el-button>
      </div>
    </header>

    <section
      ref="workspace"
      class="chat-workspace"
      :class="{ 'chat-workspace--resizing': activeResizer }"
      :style="workspaceStyle"
    >
      <SessionSidebar
        :sessions="sessions.sessions"
        :current-session-id="currentSessionId"
        :loading="sessions.loading"
        :deleting-session-id="sessions.deletingSessionId"
        :renaming-session-id="sessions.renamingSessionId"
        :cancelling-session-ids="chat.cancellingSessionIds"
        @create="createSession"
        @select="selectSession"
        @delete="deleteSession"
        @rename="renameSession"
      />

      <button
        type="button"
        class="chat-resizer chat-resizer--session"
        data-testid="session-sidebar-resizer"
        aria-label="拖动调整会话栏宽度"
        @pointerdown="startResize('session', $event)"
      />

      <div class="chat-main">
        <div class="chat-main__mode">当前模式：{{ currentModeLabel }}</div>
        <div v-if="sessions.error || chat.error" class="chat-error">
          {{ sessions.error || chat.error }}
        </div>
        <ChatTranscript
          :messages="currentMessages"
          :loading="isSending"
          :has-session="Boolean(currentSessionId)"
          :session-id="currentSessionId"
        />
        <Composer
          :disabled="chat.isLoading || sessions.loading || chat.isCancellingSession(currentSessionId)"
          :has-session="Boolean(currentSessionId)"
          :session-id="currentSessionId"
          :mode="currentMode"
          @send="sendMessage"
        />
      </div>

      <button
        type="button"
        class="chat-resizer chat-resizer--assistant"
        data-testid="assistant-panel-resizer"
        aria-label="拖动调整辅助面板宽度"
        @pointerdown="startResize('assistant', $event)"
      />

      <aside class="assistant-panel">
        <el-tabs class="assistant-tabs" stretch>
          <el-tab-pane label="Skill">
            <el-tabs class="skill-tabs" stretch>
              <el-tab-pane label="我的skill">
                <SkillPanel />
              </el-tab-pane>
              <el-tab-pane label="自进化skill审核" name="skillReview">
                <SkillReviewPanel />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
          <el-tab-pane label="知识库">
            <el-tabs class="knowledge-tabs" stretch>
              <el-tab-pane label="知识库">
                <KnowledgePanel />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </section>

    <div v-if="modeDialogVisible" class="session-mode-dialog" data-testid="session-mode-dialog">
      <div class="session-mode-dialog__panel">
        <h2>选择对话模式</h2>
        <p>会话创建后模式不可修改。</p>
        <div class="session-mode-dialog__options">
          <button type="button" data-testid="session-mode-normal" :class="{ selected: pendingMode === 'NORMAL' }" @click="pendingMode = 'NORMAL'">普通对话</button>
          <button type="button" data-testid="session-mode-knowledge" :class="{ selected: pendingMode === 'KNOWLEDGE' }" @click="pendingMode = 'KNOWLEDGE'">知识库问答</button>
        </div>
        <button type="button" data-testid="confirm-session-mode" :disabled="!pendingMode" @click="confirmCreateSession">创建会话</button>
      </div>
    </div>
  </main>
</template>
