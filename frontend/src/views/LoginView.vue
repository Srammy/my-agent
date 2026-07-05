<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '../stores/auth'

type AuthMode = 'login' | 'register'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const mode = ref<AuthMode>('login')
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
  displayName: ''
})

const title = computed(() => (mode.value === 'login' ? '登录 MyAgent' : '注册 MyAgent'))
const subtitle = computed(() => (mode.value === 'login' ? '进入你的工作台' : '创建账号后进入工作台'))

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 64, message: '用户名不能超过 64 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码长度为 8 到 72 个字符', trigger: 'blur' }
  ],
  displayName: [{ max: 64, message: '昵称不能超过 64 个字符', trigger: 'blur' }]
}

function switchMode(nextMode: AuthMode) {
  mode.value = nextMode
  formRef.value?.clearValidate()
}

function readError(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

async function submit() {
  if (!formRef.value) {
    return
  }

  const valid = await formRef.value.validate().catch(() => false)

  if (!valid) {
    return
  }

  loading.value = true

  try {
    if (mode.value === 'login') {
      await auth.login({
        username: form.username.trim(),
        password: form.password
      })
    } else {
      await auth.register({
        username: form.username.trim(),
        password: form.password,
        displayName: form.displayName.trim() || undefined
      })
    }

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(readError(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="app-page auth-page">
    <section class="auth-panel" aria-labelledby="auth-title">
      <header class="auth-header">
        <h1 id="auth-title">{{ title }}</h1>
        <p>{{ subtitle }}</p>
      </header>

      <div class="auth-body">
        <el-segmented
          v-model="mode"
          class="auth-tabs"
          :options="[
            { label: '登录', value: 'login' },
            { label: '注册', value: 'register' }
          ]"
          block
          @change="switchMode"
        />

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" autocomplete="username" maxlength="64" />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              autocomplete="current-password"
              maxlength="72"
              show-password
              type="password"
            />
          </el-form-item>

          <el-form-item v-if="mode === 'register'" label="昵称" prop="displayName">
            <el-input v-model="form.displayName" autocomplete="name" maxlength="64" />
          </el-form-item>

          <div class="auth-actions">
            <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading">
              {{ mode === 'login' ? '登录' : '注册' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </section>
  </main>
</template>
