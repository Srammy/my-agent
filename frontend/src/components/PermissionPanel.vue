<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  getPermissionMode,
  permissionModes,
  setPermissionMode,
  type PermissionMode
} from '../api/permissions'

const props = defineProps<{
  sessionId: string
  compact?: boolean
}>()

const mode = ref<PermissionMode>('DEFAULT')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const permissionModeLabels: Record<PermissionMode, string> = {
  DEFAULT: '默认',
  EXPLORE: '探索',
  ACCEPT_EDITS: '接受编辑',
  DONT_ASK: '不再询问',
  BYPASS: '绕过确认'
}

watch(
  () => props.sessionId,
  async (sessionId) => {
    error.value = ''

    if (!sessionId) {
      mode.value = 'DEFAULT'
      return
    }

    loading.value = true

    try {
      const result = await getPermissionMode(sessionId)
      mode.value = result.mode
    } catch (requestError) {
      error.value = requestError instanceof Error ? requestError.message : '权限模式加载失败'
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

async function saveMode(nextMode: PermissionMode) {
  if (!props.sessionId) {
    return
  }

  saving.value = true
  error.value = ''

  try {
    const result = await setPermissionMode(props.sessionId, nextMode)
    mode.value = result.mode
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '权限模式保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section :class="['assistant-panel-section', { 'permission-panel--compact': compact }]">
    <div v-if="!compact" class="panel-row">
      <div>
        <strong>权限模式</strong>
        <p>控制当前会话执行工具和编辑时的确认策略。</p>
      </div>
    </div>

    <el-select
      v-model="mode"
      :class="compact ? 'composer__permission-select' : 'panel-control'"
      :disabled="!sessionId || loading || saving"
      :loading="loading || saving"
      @change="saveMode"
    >
      <el-option
        v-for="item in permissionModes"
        :key="item"
        :label="`${item}（${permissionModeLabels[item]}）`"
        :value="item"
      />
    </el-select>

    <p v-if="!compact && !sessionId" class="panel-muted">选择或创建会话后可调整权限。</p>
    <p v-if="error" class="panel-error">{{ error }}</p>
  </section>
</template>
