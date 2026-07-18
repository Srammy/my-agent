import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as chatApi from '../../api/chat'
import { ApiError } from '../../api/client'
import { useSessionsStore } from '../sessions'

function session(id: string): chatApi.ChatSession {
  return {
    id,
    title: id,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z'
  }
}

describe('sessions store deletion', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('keeps a session and exposes the server error when cancellation is still running', async () => {
    vi.spyOn(chatApi, 'deleteSession').mockRejectedValue(
      new ApiError('Session cancellation is still in progress', 409, null)
    )
    const store = useSessionsStore()
    store.sessions = [session('s1')]
    store.currentSessionId = 's1'

    await expect(store.deleteSession('s1')).rejects.toThrow('Session cancellation is still in progress')

    expect(store.sessions).toEqual([session('s1')])
    expect(store.currentSessionId).toBe('s1')
    expect(store.error).toBe('Session cancellation is still in progress')
  })

  it('does not report a duplicate deletion as successful', async () => {
    let resolveDelete: (() => void) | undefined
    const deleteSession = vi.spyOn(chatApi, 'deleteSession').mockImplementation(
      () => new Promise<null>((resolve) => { resolveDelete = () => resolve(null) })
    )
    const store = useSessionsStore()
    store.sessions = [session('s1')]

    const first = store.deleteSession('s1')
    expect(store.deletingSessionId).toBe('s1')

    await expect(store.deleteSession('s1')).rejects.toThrow('Session deletion is already in progress')
    expect(deleteSession).toHaveBeenCalledTimes(1)
    expect(store.sessions).toEqual([session('s1')])

    resolveDelete?.()
    await first
    expect(store.sessions).toEqual([])
    expect(store.deletingSessionId).toBe('')
  })
})
