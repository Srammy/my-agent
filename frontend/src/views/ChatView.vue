<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import ChatTranscript from '../components/ChatTranscript.vue'
import Composer from '../components/Composer.vue'
import ModelInfoPanel from '../components/ModelInfoPanel.vue'
import PermissionPanel from '../components/PermissionPanel.vue'
import SessionSidebar from '../components/SessionSidebar.vue'
import SkillPanel from '../components/SkillPanel.vue'
import SkillReviewPanel from '../components/SkillReviewPanel.vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useSessionsStore } from '../stores/sessions'

const auth = useAuthStore()
const chat = useChatStore()
const router = useRouter()
const sessions = useSessionsStore()

const currentSessionId = computed(() => sessions.currentSessionId)
const currentMessages = computed(() =>
  currentSessionId.value ? chat.messages(currentSessionId.value) : []
)
const isSending = computed(() => chat.loadingSessionId === currentSessionId.value)

watch(
  currentSessionId,
  (sessionId) => {
    if (sessionId) {
      chat.useSession(sessionId)
    }
  },
  { immediate: true }
)

onMounted(async () => {
  await sessions.loadSessions()
})

async function createSession() {
  const session = await sessions.createSession()
  chat.useSession(session.id)
}

function selectSession(sessionId: string) {
  sessions.selectSession(sessionId)
  chat.useSession(sessionId)
}

async function deleteSession(sessionId: string) {
  await sessions.deleteSession(sessionId)
  chat.clearSession(sessionId)
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
        <span>{{ auth.user?.username }}</span>
      </div>
      <div class="chat-topbar__actions">
        <span v-if="sessions.currentSession" class="chat-topbar__session">
          {{ sessions.currentSession.title || '新会话' }}
        </span>
        <el-button @click="logout">退出登录</el-button>
      </div>
    </header>

    <section class="chat-workspace">
      <SessionSidebar
        :sessions="sessions.sessions"
        :current-session-id="currentSessionId"
        :loading="sessions.loading"
        @create="createSession"
        @select="selectSession"
        @delete="deleteSession"
      />

      <div class="chat-main">
        <div v-if="sessions.error || chat.error" class="chat-error">
          {{ sessions.error || chat.error }}
        </div>
        <ChatTranscript
          :messages="currentMessages"
          :loading="isSending"
          :has-session="Boolean(currentSessionId)"
        />
        <Composer
          :disabled="isSending || sessions.loading"
          :has-session="Boolean(currentSessionId)"
          @send="sendMessage"
        />
      </div>

      <aside class="assistant-panel">
        <el-tabs class="assistant-tabs" stretch>
          <el-tab-pane label="Model">
            <ModelInfoPanel />
          </el-tab-pane>
          <el-tab-pane label="Permission">
            <PermissionPanel :session-id="currentSessionId" />
          </el-tab-pane>
          <el-tab-pane label="Skill">
            <SkillPanel />
          </el-tab-pane>
          <el-tab-pane label="Skill 审核" name="skillReview">
            <SkillReviewPanel />
          </el-tab-pane>
        </el-tabs>
      </aside>
    </section>
  </main>
</template>
