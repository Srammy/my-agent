import { defineStore } from 'pinia'
import { deleteMySkill, listMySkills, uploadSkill, type Skill } from '../api/skills'

interface SkillsState {
  mySkills: Skill[]
  loading: boolean
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export const useSkillsStore = defineStore('skills', {
  state: (): SkillsState => ({
    mySkills: [],
    loading: false,
    error: ''
  }),
  actions: {
    async loadSkills() {
      this.loading = true
      this.error = ''
      try {
        this.mySkills = await listMySkills()
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async uploadSkill(files: File[]) {
      this.error = ''
      try {
        const skill = await uploadSkill(files)
        this.mySkills = [...this.mySkills, skill].sort((a, b) => a.name.localeCompare(b.name))
        return skill
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async deleteSkill(skillName: string) {
      this.error = ''
      try {
        await deleteMySkill(skillName)
        this.mySkills = this.mySkills.filter((s) => s.name !== skillName)
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    }
  }
})
