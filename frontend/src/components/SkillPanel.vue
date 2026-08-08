<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useSkillsStore } from '../stores/skills'

const skills = useSkillsStore()
const fileInput = ref<HTMLInputElement | null>(null)

onMounted(() => {
  skills.loadSkills()
})

function triggerUpload() {
  fileInput.value?.click()
}

async function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (!files.length) return
  try {
    await skills.uploadSkill(files)
  } finally {
    input.value = ''
  }
}
</script>

<template>
  <section class="assistant-panel-section" v-loading="skills.loading">
    <div class="panel-row">
      <strong>我的 Skill</strong>
      <el-tooltip placement="top" effect="light">
        <template #content>
          <div class="skill-upload-tooltip">
            <div>请确认目录结构类似：</div>
            <pre>my-skill/
├─ SKILL.md
├─ references/
├─ scripts/
└─ assets/</pre>
          </div>
        </template>
        <el-button size="small" @click="triggerUpload">上传</el-button>
      </el-tooltip>
      <input
        ref="fileInput"
        type="file"
        multiple
        webkitdirectory
        style="display: none"
        @change="handleFiles"
      />
    </div>

    <ul class="skill-list">
      <li v-for="skill in skills.mySkills" :key="skill.name" class="skill-list-item">
        <div class="skill-info">
          <span class="skill-name">{{ skill.name }}</span>
          <el-tooltip placement="top" effect="light">
            <template #content>
              <div class="skill-description-tooltip">{{ skill.description }}</div>
            </template>
            <span class="skill-description panel-muted">{{ skill.description }}</span>
          </el-tooltip>
        </div>
        <el-button size="small" type="danger" @click="skills.deleteSkill(skill.name)">
          删除
        </el-button>
      </li>
    </ul>

    <p v-if="!skills.mySkills.length && !skills.loading" class="panel-muted">
      暂无 Skill，请上传包含 SKILL.md 的文件集合。
    </p>

    <p v-if="skills.error" class="panel-error">{{ skills.error }}</p>
  </section>
</template>
