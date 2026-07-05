import { apiGet } from './client'

export interface MemorySummary {
  content: string
}

export interface MemoryDailyList {
  items: string[]
}

export interface MemoryDaily {
  date: string
  content: string
}

export function getMemorySummary() {
  return apiGet<MemorySummary>('/api/memory/summary')
}

export function listDailyMemory() {
  return apiGet<MemoryDailyList>('/api/memory/daily')
}

export function getDailyMemory(date: string) {
  return apiGet<MemoryDaily>(`/api/memory/daily/${encodeURIComponent(date)}`)
}
