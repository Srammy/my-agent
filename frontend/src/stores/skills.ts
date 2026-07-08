import { defineStore } from 'pinia'
import {
  createMySkill,
  deleteMySkill,
  deleteSkillFile,
  listMySkills,
  listSkillFiles,
  updateMySkill,
  upsertSkillFile,
  type Skill,
  type SkillCreatePayload,
  type SkillFile
} from '../api/skills'

interface SkillsState {
  mySkills: Skill[]
  filesBySkillName: Record<string, SkillFile[]>
  loading: boolean
  filesLoading: boolean
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '璇锋眰澶辫触锛岃绋嶅悗閲嶈瘯'
}

export const useSkillsStore = defineStore('skills', {
  state: (): SkillsState => ({
    mySkills: [],
    filesBySkillName: {},
    loading: false,
    filesLoading: false,
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
    async createSkill(payload: SkillCreatePayload) {
      this.error = ''
      try {
        const skill = await createMySkill(payload)
        this.mySkills = [skill, ...this.mySkills].sort((left, right) => left.name.localeCompare(right.name))
        return skill
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async updateSkill(skillName: string, payload: SkillCreatePayload) {
      this.error = ''
      try {
        const skill = await updateMySkill(skillName, payload)
        this.mySkills = this.mySkills
          .map((item) => (item.name === skillName ? skill : item))
          .sort((left, right) => left.name.localeCompare(right.name))
        if (skillName !== skill.name) {
          this.filesBySkillName[skill.name] = this.filesBySkillName[skillName] ?? []
          delete this.filesBySkillName[skillName]
        }
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
        this.mySkills = this.mySkills.filter((skill) => skill.name !== skillName)
        delete this.filesBySkillName[skillName]
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async loadFiles(skillName: string) {
      this.filesLoading = true
      this.error = ''

      try {
        this.filesBySkillName[skillName] = await listSkillFiles(skillName)
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.filesLoading = false
      }
    },
    async saveFile(skillName: string, path: string, content: string) {
      this.error = ''
      try {
        const file = await upsertSkillFile(skillName, path, content)
        const files = this.filesBySkillName[skillName] ?? []
        this.filesBySkillName[skillName] = [
          file,
          ...files.filter((item) => item.path !== file.path)
        ].sort((left, right) => left.path.localeCompare(right.path))
        return file
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async deleteFile(skillName: string, path: string) {
      this.error = ''
      try {
        await deleteSkillFile(skillName, path)
        this.filesBySkillName[skillName] = (this.filesBySkillName[skillName] ?? []).filter(
          (file) => file.path !== path
        )
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    }
  }
})
