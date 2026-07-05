<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SkillFile } from '../api/skills'

const props = defineProps<{
  files: SkillFile[]
  loading: boolean
}>()

const emit = defineEmits<{
  save: [path: string, content: string]
  delete: [path: string]
}>()

const selectedPath = ref('')
const draftPath = ref('SKILL.md')
const draftContent = ref('')

const sortedFiles = computed(() =>
  [...props.files].sort((left, right) => left.path.localeCompare(right.path))
)
const selectedFile = computed(() => props.files.find((file) => file.path === selectedPath.value))
const canDelete = computed(() => Boolean(selectedPath.value) && selectedPath.value !== 'SKILL.md')

watch(
  () => props.files,
  (files) => {
    if (!selectedPath.value && files.length) {
      selectFile(files[0])
    } else if (selectedPath.value && !files.some((file) => file.path === selectedPath.value)) {
      selectFile(files[0])
    }
  },
  { immediate: true }
)

function selectFile(file?: SkillFile) {
  selectedPath.value = file?.path ?? ''
  draftPath.value = file?.path ?? 'SKILL.md'
  draftContent.value = file?.content ?? ''
}

function saveFile() {
  const path = draftPath.value.trim()

  if (!path) {
    return
  }

  emit('save', path, draftContent.value)
  selectedPath.value = path
}
</script>

<template>
  <div class="skill-files" v-loading="loading">
    <div class="skill-file-list">
      <button
        v-for="file in sortedFiles"
        :key="file.path"
        class="skill-file-item"
        :class="{ 'skill-file-item--active': file.path === selectedPath }"
        type="button"
        @click="selectFile(file)"
      >
        {{ file.path }}
      </button>
      <button class="skill-file-item" type="button" @click="selectFile()">+ 新文件</button>
    </div>

    <div class="skill-file-editor">
      <el-input v-model="draftPath" size="small" placeholder="SKILL.md 或 references/example.md" />
      <el-input
        v-model="draftContent"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 14 }"
        placeholder="文件内容"
      />
      <div class="skill-file-actions">
        <el-button size="small" type="primary" @click="saveFile">保存</el-button>
        <el-button
          size="small"
          type="danger"
          :disabled="!canDelete"
          @click="emit('delete', selectedPath)"
        >
          删除
        </el-button>
      </div>
      <p v-if="selectedFile?.updatedAt" class="panel-muted">更新于 {{ selectedFile.updatedAt }}</p>
    </div>
  </div>
</template>
