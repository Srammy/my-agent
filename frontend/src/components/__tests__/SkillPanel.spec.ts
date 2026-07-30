import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import * as skillsApi from '../../api/skills'
import SkillPanel from '../SkillPanel.vue'

vi.mock('../../api/skills', () => ({
  listMySkills: vi.fn(),
  uploadSkill: vi.fn(),
  deleteMySkill: vi.fn()
}))

describe('SkillPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(skillsApi.listMySkills).mockResolvedValue([])
    vi.mocked(skillsApi.uploadSkill).mockResolvedValue({
      name: 'java-helper',
      description: 'Java helper'
    })
  })

  it('uses a directory input for a complete Skill upload', () => {
    const wrapper = mount(SkillPanel, { global: { plugins: [ElementPlus] } })
    const input = wrapper.get('input[type="file"]')

    expect(input.attributes('multiple')).toBeDefined()
    expect(input.attributes('webkitdirectory')).toBeDefined()
  })

  it('clears the directory input after an upload failure', async () => {
    vi.mocked(skillsApi.uploadSkill).mockRejectedValueOnce(new Error('upload failed'))
    const errorHandler = vi.fn()
    const wrapper = mount(SkillPanel, {
      global: {
        plugins: [ElementPlus],
        config: { errorHandler }
      }
    })
    const input = wrapper.get('input[type="file"]')
    const files = [new File(['content'], 'SKILL.md')]
    let inputValue = 'selected-directory'
    Object.defineProperty(input.element, 'files', { configurable: true, value: files })
    Object.defineProperty(input.element, 'value', {
      configurable: true,
      get: () => inputValue,
      set: (value: string) => { inputValue = value }
    })

    await input.trigger('change')
    await vi.waitFor(() => expect(errorHandler).toHaveBeenCalled())

    expect(inputValue).toBe('')
  })
})
