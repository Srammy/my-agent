import { defineStore } from 'pinia'
import {
  createMySkill,
  deleteMySkill,
  deleteSkillFile,
  listMySkills,
  listSkillFiles,
  listSystemSkills,
  setSkillEnabled,
  updateMySkill,
  upsertSkillFile,
  type Skill,
  type SkillCreatePayload,
  type SkillFile
} from '../api/skills'

interface SkillsState {
  systemSkills: Skill[]
  mySkills: Skill[]
  filesBySkillId: Record<number, SkillFile[]>
  loading: boolean
  filesLoading: boolean
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function replaceSkill(items: Skill[], skill: Skill) {
  return items.map((item) => (item.id === skill.id ? skill : item))
}

export const useSkillsStore = defineStore('skills', {
  state: (): SkillsState => ({
    systemSkills: [],
    mySkills: [],
    filesBySkillId: {},
    loading: false,
    filesLoading: false,
    error: ''
  }),
  actions: {
    async loadSkills() {
      this.loading = true
      this.error = ''

      try {
        const [systemSkills, mySkills] = await Promise.all([listSystemSkills(), listMySkills()])
        this.systemSkills = systemSkills
        this.mySkills = mySkills
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async toggleEnabled(skill: Skill, enabled: boolean) {
      this.error = ''
      try {
        const updated = await setSkillEnabled(skill.id, enabled)

        if (skill.ownerType === 'SYSTEM') {
          this.systemSkills = replaceSkill(this.systemSkills, updated)
        } else {
          this.mySkills = replaceSkill(this.mySkills, updated)
        }
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async createSkill(payload: SkillCreatePayload) {
      this.error = ''
      try {
        const skill = await createMySkill(payload)
        this.mySkills = [skill, ...this.mySkills]
        return skill
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async updateSkill(skillId: number, payload: SkillCreatePayload) {
      this.error = ''
      try {
        const skill = await updateMySkill(skillId, payload)
        this.mySkills = replaceSkill(this.mySkills, skill)
        return skill
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async deleteSkill(skillId: number) {
      this.error = ''
      try {
        await deleteMySkill(skillId)
        this.mySkills = this.mySkills.filter((skill) => skill.id !== skillId)
        delete this.filesBySkillId[skillId]
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async loadFiles(skillId: number) {
      this.filesLoading = true
      this.error = ''

      try {
        this.filesBySkillId[skillId] = await listSkillFiles(skillId)
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.filesLoading = false
      }
    },
    async saveFile(skillId: number, path: string, content: string) {
      this.error = ''
      try {
        const file = await upsertSkillFile(skillId, path, content)
        const files = this.filesBySkillId[skillId] ?? []
        this.filesBySkillId[skillId] = [
          file,
          ...files.filter((item) => item.path !== file.path)
        ].sort((left, right) => left.path.localeCompare(right.path))
        return file
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async deleteFile(skillId: number, path: string) {
      this.error = ''
      try {
        await deleteSkillFile(skillId, path)
        this.filesBySkillId[skillId] = (this.filesBySkillId[skillId] ?? []).filter(
          (file) => file.path !== path
        )
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    }
  }
})
