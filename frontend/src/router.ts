import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import ChatView from './views/ChatView.vue'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/login',
      component: LoginView
    },
    {
      path: '/chat',
      component: ChatView,
      meta: { requiresAuth: true }
    }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (auth.token && !auth.user) {
    try {
      await auth.loadMe()
    } catch {
      auth.logout()
    }
  }

  if (to.path === '/login' && auth.isAuthenticated) {
    return '/chat'
  }

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return {
      path: '/login',
      query: { redirect: to.fullPath }
    }
  }

  return true
})

export default router
