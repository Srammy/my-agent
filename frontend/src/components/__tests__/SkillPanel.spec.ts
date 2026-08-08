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

  it('shows the required Skill directory structure in the upload tooltip', async () => {
    const wrapper = mount(SkillPanel, { global: { plugins: [ElementPlus] } })
    const tooltip = wrapper.findComponent({ name: 'ElTooltip' })

    expect(tooltip.exists()).toBe(true)

    await wrapper.get('button').trigger('mouseenter')
    await vi.waitFor(() => expect(document.body.querySelector('.skill-upload-tooltip')).not.toBeNull())

    const content = document.body.querySelector<HTMLElement>('.skill-upload-tooltip')
    expect(content?.textContent).toContain('请确认目录结构类似：')
    expect(content?.textContent).toContain('my-skill/')
    expect(content?.textContent).toContain('├─ SKILL.md')
    expect(content?.textContent).toContain('└─ assets/')

    wrapper.unmount()
  })

  it('renders skill name and description as separate fields', async () => {
    vi.mocked(skillsApi.listMySkills).mockResolvedValueOnce([
      {
        name: 'code-reviewer',
        description: 'Use this agent when you need to conduct comprehensive code reviews'
      }
    ])
    const wrapper = mount(SkillPanel, { global: { plugins: [ElementPlus] } })

    await vi.waitFor(() => expect(wrapper.find('.skill-list-item').exists()).toBe(true))

    const item = wrapper.get('.skill-list-item')
    expect(item.find('.skill-info').exists()).toBe(true)
    expect(item.get('.skill-name').text()).toBe('code-reviewer')
    expect(item.get('.skill-description').text())
      .toBe('Use this agent when you need to conduct comprehensive code reviews')
  })

  it('shows the complete multiline description when hovering a Skill description', async () => {
    vi.mocked(skillsApi.listMySkills).mockResolvedValueOnce([
      {
        name: 'code-reviewer',
        description: 'Review pull requests thoroughly.\nPreserve this second line.'
      }
    ])
    const wrapper = mount(SkillPanel, { global: { plugins: [ElementPlus] } })

    await vi.waitFor(() => expect(wrapper.find('.skill-description').exists()).toBe(true))
    await wrapper.get('.skill-description').trigger('mouseenter')
    await vi.waitFor(() => expect(document.body.querySelector('.skill-description-tooltip')).not.toBeNull())

    const content = document.body.querySelector<HTMLElement>('.skill-description-tooltip')
    expect(content?.textContent).toBe('Review pull requests thoroughly.\nPreserve this second line.')

    wrapper.unmount()
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
