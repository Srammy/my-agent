import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { listSkillReviews, approveSkillReview, rejectSkillReview } = vi.hoisted(() => ({
  listSkillReviews: vi.fn(),
  approveSkillReview: vi.fn(),
  rejectSkillReview: vi.fn()
}))

vi.mock('../../api/skillReviews', () => ({
  listSkillReviews,
  approveSkillReview,
  rejectSkillReview
}))

import { useSkillReviewsStore } from '../skillReviews'

const existingReview = {
  skillName: 'my-skill',
  description: 'Keep this description',
  status: 'PENDING',
  createdBy: 'user-1',
  environments: [],
  useCount: 0,
  viewCount: 0,
  patchCount: 0
}

describe('skillReviews store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listSkillReviews.mockReset()
    approveSkillReview.mockReset()
    rejectSkillReview.mockReset()
  })

  it('preserves the existing description when approval response omits it', async () => {
    const store = useSkillReviewsStore()
    store.reviews.push(existingReview)
    approveSkillReview.mockResolvedValue({
      ...existingReview,
      description: null,
      status: 'APPROVED',
      environments: ['prod']
    })

    await store.approve('my-skill', ['prod'])

    expect(store.reviews[0]).toMatchObject({
      skillName: 'my-skill',
      description: 'Keep this description',
      status: 'APPROVED',
      environments: ['prod']
    })
  })
})
