<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { Skill } from '../api/skills'
import { useSkillsStore } from '../stores/skills'
import SkillFileTree from './SkillFileTree.vue'

const skills = useSkillsStore()
const selectedMySkillName = ref<string | null>(null)
const editingSkillName = ref<string | null>(null)
const nameDraft = ref('')
const descriptionDraft = ref('')

const selectedMySkill = computed(
  () => skills.mySkills.find((skill) => skill.name === selectedMySkillName.value) ?? null
)
const selectedFiles = computed(() =>
  selectedMySkillName.value ? skills.filesBySkillName[selectedMySkillName.value] ?? [] : []
)

onMounted(() => {
  skills.loadSkills()
})

watch(
  () => skills.mySkills,
  (items) => {
    if (!selectedMySkillName.value && items.length) {
      selectMySkill(items[0])
    }
  }
)

function beginCreate() {
  editingSkillName.value = null
  selectedMySkillName.value = null
  nameDraft.value = ''
  descriptionDraft.value = ''
}

function beginEdit(skill: Skill) {
  editingSkillName.value = skill.name
  nameDraft.value = skill.name
  descriptionDraft.value = skill.description
}

async function submitSkill() {
  const payload = {
    name: nameDraft.value.trim(),
    description: descriptionDraft.value.trim()
  }

  if (!payload.name) {
    return
  }

  const skill = editingSkillName.value
    ? await skills.updateSkill(editingSkillName.value, payload)
    : await skills.createSkill(payload)

  editingSkillName.value = skill.name
  selectedMySkillName.value = skill.name
  await skills.loadFiles(skill.name)
}

async function removeSkill(skill: Skill) {
  await skills.deleteSkill(skill.name)
  selectedMySkillName.value = skills.mySkills[0]?.name ?? null
}

async function selectMySkill(skill: Skill) {
  selectedMySkillName.value = skill.name
  beginEdit(skill)
  await skills.loadFiles(skill.name)
}

function saveFile(path: string, content: string) {
  if (selectedMySkillName.value) {
    skills.saveFile(selectedMySkillName.value, path, content)
  }
}

function deleteFile(path: string) {
  if (selectedMySkillName.value && path !== 'SKILL.md') {
    skills.deleteFile(selectedMySkillName.value, path)
  }
}
</script>

<template>
  <section class="assistant-panel-section" v-loading="skills.loading">
    <div class="panel-row">
      <strong>我的 Skill</strong>
      <el-button size="small" @click="beginCreate">新建</el-button>
    </div>

    <div class="skill-layout">
      <div class="skill-list">
        <button
          v-for="skill in skills.mySkills"
          :key="skill.name"
          class="skill-list-item"
          :class="{ 'skill-list-item--active': skill.name === selectedMySkillName }"
          type="button"
          @click="selectMySkill(skill)"
        >
          {{ skill.name }}
        </button>
      </div>

      <div class="skill-editor">
        <el-input v-model="nameDraft" size="small" placeholder="Skill 名称" />
        <el-input
          v-model="descriptionDraft"
          size="small"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="描述"
        />
        <div class="skill-actions">
          <el-button size="small" type="primary" :disabled="!nameDraft.trim()" @click="submitSkill">
            保存
          </el-button>
          <el-button
            size="small"
            type="danger"
            :disabled="!selectedMySkill"
            @click="selectedMySkill && removeSkill(selectedMySkill)"
          >
            删除
          </el-button>
        </div>
      </div>
    </div>

    <SkillFileTree
      v-if="selectedMySkill"
      :files="selectedFiles"
      :loading="skills.filesLoading"
      @save="saveFile"
      @delete="deleteFile"
    />
    <p v-else class="panel-muted">选择或创建一个 Skill 后编辑文件。</p>

    <p v-if="skills.error" class="panel-error">{{ skills.error }}</p>
  </section>
</template>
