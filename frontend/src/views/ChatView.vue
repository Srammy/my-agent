<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

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
      <el-button @click="logout">退出登录</el-button>
    </header>

    <section class="chat-workspace">
      <div class="chat-placeholder">
        <h1>工作台已就绪</h1>
        <p>登录状态和路由守卫已接入，聊天工作流将在后续任务中完成。</p>
      </div>
    </section>
  </main>
</template>
