import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import * as skillsApi from '../../api/skills'
import * as skillReviewsApi from '../../api/skillReviews'
import SkillReviewPanel from '../SkillReviewPanel.vue'

vi.mock('../../api/skills', () => ({
  listMySkills: vi.fn()
}))

vi.mock('../../api/skillReviews', () => ({
  listSkillReviews: vi.fn(),
  approveSkillReview: vi.fn(),
  rejectSkillReview: vi.fn()
}))

describe('SkillReviewPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(skillsApi.listMySkills).mockResolvedValue([])
    vi.mocked(skillReviewsApi.listSkillReviews).mockResolvedValue([
      {
        skillName: 'tv-show-recommender',
        description: 'TV shows',
        status: 'PENDING',
        createdBy: null,
        sourceSessionId: null,
        environments: [],
        useCount: 0,
        viewCount: 0,
        patchCount: 0
      }
    ])
    vi.mocked(skillReviewsApi.approveSkillReview).mockResolvedValue({
      skillName: 'tv-show-recommender',
      description: 'TV shows',
      status: 'APPROVED',
      createdBy: null,
      sourceSessionId: null,
      environments: ['prod'],
      useCount: 0,
      viewCount: 0,
      patchCount: 0
    })
  })

  it('reloads the current users formal Skills after approval', async () => {
    const wrapper = mount(SkillReviewPanel, { global: { plugins: [ElementPlus] } })

    await vi.waitFor(() => expect(wrapper.find('.el-table').exists()).toBe(true))
    const approveButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('批准'))
    expect(approveButton).toBeDefined()

    await approveButton!.trigger('click')

    await vi.waitFor(() => expect(skillsApi.listMySkills).toHaveBeenCalledOnce())
  })

  it('loads formal Skills after the review list repairs approved drafts', async () => {
    mount(SkillReviewPanel, { global: { plugins: [ElementPlus] } })

    await vi.waitFor(() => expect(skillsApi.listMySkills).toHaveBeenCalledOnce())
  })
})
