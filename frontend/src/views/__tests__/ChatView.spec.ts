import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { shallowMount } from '@vue/test-utils'
import * as chatApi from '../../api/chat'
import { ApiError } from '../../api/client'
import SessionSidebar from '../../components/SessionSidebar.vue'
import { useChatStore } from '../../stores/chat'
import { useSessionsStore } from '../../stores/sessions'
import ChatView from '../ChatView.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() })
}))

const session: chatApi.ChatSession = {
  id: 's1',
  title: 'Session 1',
  createdAt: '2026-07-18T00:00:00Z',
  updatedAt: '2026-07-18T00:00:00Z'
}

async function mountView() {
  vi.spyOn(chatApi, 'listSessions').mockResolvedValue([])
  const wrapper = shallowMount(ChatView, {
    global: {
      stubs: {
        ElButton: true,
        ElTabPane: true,
        ElTabs: true
      }
    }
  })
  await Promise.resolve()
  return wrapper
}

describe('ChatView session deletion', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('aborts locally first and clears messages only after DELETE succeeds', async () => {
    let resolveDelete: (() => void) | undefined
    vi.spyOn(chatApi, 'deleteSession').mockImplementation(
      () => new Promise<null>((resolve) => { resolveDelete = () => resolve(null) })
    )
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [session]
    sessions.currentSessionId = 's1'
    chat.messagesBySession.s1 = [{ id: 'm1', role: 'user', content: 'hello', events: [] }]
    const abort = vi.spyOn(chat, 'abortSession')

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')
    await Promise.resolve()

    expect(abort).toHaveBeenCalledWith('s1')
    expect(chat.messages('s1')).toHaveLength(1)

    resolveDelete?.()
    await vi.waitFor(() => expect(sessions.sessions).toEqual([]))

    expect(chat.messagesBySession).not.toHaveProperty('s1')
  })

  it('keeps messages and leaves cancellation recoverable when DELETE fails', async () => {
    vi.spyOn(chatApi, 'deleteSession').mockRejectedValue(
      new ApiError('Session cancellation is still in progress', 409, null)
    )
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [session]
    sessions.currentSessionId = 's1'
    chat.messagesBySession.s1 = [{ id: 'm1', role: 'user', content: 'hello', events: [] }]

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')

    await vi.waitFor(() => expect(sessions.error).toBe('Session cancellation is still in progress'))
    expect(sessions.sessions).toEqual([session])
    expect(chat.messages('s1')).toHaveLength(1)
    expect(chat.isCancellingSession('s1')).toBe(false)
  })
})
