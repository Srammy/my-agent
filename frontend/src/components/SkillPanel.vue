<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { Skill } from '../api/skills'
import { useSkillsStore } from '../stores/skills'
import SkillFileTree from './SkillFileTree.vue'

const skills = useSkillsStore()
const activeTab = ref('system')
const selectedMySkillId = ref<number | null>(null)
const editingSkillId = ref<number | null>(null)
const nameDraft = ref('')
const descriptionDraft = ref('')

const selectedMySkill = computed(
  () => skills.mySkills.find((skill) => skill.id === selectedMySkillId.value) ?? null
)
const selectedFiles = computed(() =>
  selectedMySkillId.value ? skills.filesBySkillId[selectedMySkillId.value] ?? [] : []
)

onMounted(() => {
  skills.loadSkills()
})

watch(
  () => skills.mySkills,
  (items) => {
    if (!selectedMySkillId.value && items.length) {
      selectMySkill(items[0])
    }
  }
)

function beginCreate() {
  editingSkillId.value = null
  selectedMySkillId.value = null
  nameDraft.value = ''
  descriptionDraft.value = ''
}

function beginEdit(skill: Skill) {
  editingSkillId.value = skill.id
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

  const skill = editingSkillId.value
    ? await skills.updateSkill(editingSkillId.value, payload)
    : await skills.createSkill(payload)

  editingSkillId.value = skill.id
  selectedMySkillId.value = skill.id
  await skills.loadFiles(skill.id)
}

async function removeSkill(skill: Skill) {
  await skills.deleteSkill(skill.id)
  selectedMySkillId.value = skills.mySkills[0]?.id ?? null
}

async function selectMySkill(skill: Skill) {
  selectedMySkillId.value = skill.id
  beginEdit(skill)
  await skills.loadFiles(skill.id)
}

function saveFile(path: string, content: string) {
  if (selectedMySkillId.value) {
    skills.saveFile(selectedMySkillId.value, path, content)
  }
}

function deleteFile(path: string) {
  if (selectedMySkillId.value && path !== 'SKILL.md') {
    skills.deleteFile(selectedMySkillId.value, path)
  }
}
</script>

<template>
  <section class="assistant-panel-section" v-loading="skills.loading">
    <el-tabs v-model="activeTab" class="assistant-inner-tabs">
      <el-tab-pane label="公共 Skill" name="system">
        <div v-if="!skills.systemSkills.length" class="panel-muted">暂无公共 Skill。</div>
        <div v-for="skill in skills.systemSkills" :key="skill.id" class="skill-item">
          <div>
            <strong>{{ skill.name }}</strong>
            <p>{{ skill.description || '无描述。' }}</p>
          </div>
          <el-switch
            :model-value="skill.enabled"
            @change="(value) => skills.toggleEnabled(skill, Boolean(value))"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="我的 Skill" name="mine">
        <div class="panel-row">
          <strong>我的 Skill</strong>
          <el-button size="small" @click="beginCreate">新建</el-button>
        </div>

        <div class="skill-layout">
          <div class="skill-list">
            <button
              v-for="skill in skills.mySkills"
              :key="skill.id"
              class="skill-list-item"
              :class="{ 'skill-list-item--active': skill.id === selectedMySkillId }"
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
      </el-tab-pane>
    </el-tabs>

    <p v-if="skills.error" class="panel-error">{{ skills.error }}</p>
  </section>
</template>
