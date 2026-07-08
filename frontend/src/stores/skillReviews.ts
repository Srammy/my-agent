import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  listSkillReviews,
  approveSkillReview,
  rejectSkillReview,
  type SkillReview
} from '../api/skillReviews'

export const useSkillReviewsStore = defineStore('skillReviews', () => {
  const reviews = ref<SkillReview[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function loadReviews() {
    loading.value = true
    error.value = null
    try {
      reviews.value = await listSkillReviews()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load reviews'
    } finally {
      loading.value = false
    }
  }

  async function approve(skillName: string, environments: string[]) {
    const updated = await approveSkillReview(skillName, environments)
    const idx = reviews.value.findIndex(r => r.skillName === skillName)
    if (idx >= 0) reviews.value[idx] = updated
    else reviews.value.push(updated)
  }

  async function reject(skillName: string, reason: string) {
    const updated = await rejectSkillReview(skillName, reason)
    const idx = reviews.value.findIndex(r => r.skillName === skillName)
    if (idx >= 0) reviews.value[idx] = updated
    else reviews.value.push(updated)
  }

  return { reviews, loading, error, loadReviews, approve, reject }
})
