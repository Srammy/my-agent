import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as chatApi from '../../api/chat'
import { ApiError } from '../../api/client'
import { useSessionsStore } from '../sessions'

function session(id: string): chatApi.ChatSession {
  return {
    id,
    title: id,
    mode: 'NORMAL',
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z'
  }
}

describe('sessions store creation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('creates and selects a session with the requested mode', async () => {
    vi.spyOn(chatApi, 'createSession').mockResolvedValue({
      ...session('knowledge-1'),
      title: 'Knowledge',
      mode: 'KNOWLEDGE'
    })
    const store = useSessionsStore()

    await store.createSession('Knowledge', 'KNOWLEDGE')

    expect(chatApi.createSession).toHaveBeenCalledWith('Knowledge', 'KNOWLEDGE')
    expect(store.currentSessionId).toBe('knowledge-1')
    expect(store.currentSession?.mode).toBe('KNOWLEDGE')
  })
})

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

  it('does not swallow a 404 for a session that is not locally known', async () => {
    vi.spyOn(chatApi, 'deleteSession').mockRejectedValue(
      new ApiError('Session not found', 404, null)
    )
    const store = useSessionsStore()
    store.sessions = [session('s1')]

    await expect(store.deleteSession('s2')).rejects.toThrow('Session not found')

    expect(store.sessions).toEqual([session('s1')])
    expect(store.error).toBe('Session not found')
    expect(store.deletingSessionId).toBe('')
  })
})

describe('sessions store renaming', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('updates and resorts a session after a successful rename', async () => {
    vi.spyOn(chatApi, 'renameSession').mockResolvedValue({
      ...session('s1'),
      title: 'Renamed',
      updatedAt: '2026-07-18T01:00:00Z'
    })
    const store = useSessionsStore()
    store.sessions = [
      { ...session('s2'), updatedAt: '2026-07-18T00:30:00Z' },
      session('s1')
    ]

    await store.renameSession('s1', 'Renamed')

    expect(chatApi.renameSession).toHaveBeenCalledWith('s1', 'Renamed')
    expect(store.sessions.map((item) => item.id)).toEqual(['s1', 's2'])
    expect(store.sessions[0].title).toBe('Renamed')
    expect(store.renamingSessionId).toBe('')
  })

  it('keeps the old title and exposes the error when renaming fails', async () => {
    vi.spyOn(chatApi, 'renameSession').mockRejectedValue(new ApiError('Session not found', 404, null))
    const store = useSessionsStore()
    store.sessions = [session('s1')]

    await expect(store.renameSession('s1', 'Renamed')).rejects.toThrow('Session not found')

    expect(store.sessions).toEqual([session('s1')])
    expect(store.error).toBe('Session not found')
    expect(store.renamingSessionId).toBe('')
  })

  it('blocks duplicate renames while one is in progress', async () => {
    let resolveRename: ((session: chatApi.ChatSession) => void) | undefined
    vi.spyOn(chatApi, 'renameSession').mockImplementation(
      () => new Promise<chatApi.ChatSession>((resolve) => { resolveRename = resolve })
    )
    const store = useSessionsStore()
    store.sessions = [session('s1')]

    const first = store.renameSession('s1', 'One')
    expect(store.renamingSessionId).toBe('s1')

    await expect(store.renameSession('s1', 'Two')).rejects.toThrow('Session rename is already in progress')
    expect(chatApi.renameSession).toHaveBeenCalledTimes(1)

    resolveRename?.({ ...session('s1'), title: 'One' })
    await first
    expect(store.renamingSessionId).toBe('')
  })
})
