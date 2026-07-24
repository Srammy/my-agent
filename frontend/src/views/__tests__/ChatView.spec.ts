import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { shallowMount } from '@vue/test-utils'
import * as chatApi from '../../api/chat'
import { ApiError } from '../../api/client'
import SessionSidebar from '../../components/SessionSidebar.vue'
import ToolEventCard from '../../components/ToolEventCard.vue'
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

  it.each([
    ['HTTP 409', new ApiError('Session cancellation is still in progress', 409, null)],
    ['a network error', new Error('Network unavailable')]
  ])('keeps a session cancelling after DELETE fails with %s', async (_label, error) => {
    vi.spyOn(chatApi, 'deleteSession').mockRejectedValue(error)
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [session]
    sessions.currentSessionId = 's1'
    chat.messagesBySession.s1 = [{ id: 'm1', role: 'user', content: 'hello', events: [] }]

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')

    await vi.waitFor(() => expect(sessions.error).toBe(error.message))
    expect(sessions.sessions).toEqual([session])
    expect(chat.messages('s1')).toHaveLength(1)
    expect(chat.isCancellingSession('s1')).toBe(true)
    expect(wrapper.findComponent({ name: 'Composer' }).props('disabled')).toBe(true)
    expect(wrapper.findComponent(SessionSidebar).props('cancellingSessionIds')).toEqual({ s1: true })
  })

  it('clears a locally known cancelling session when retrying DELETE returns 404', async () => {
    vi.spyOn(chatApi, 'deleteSession')
      .mockRejectedValueOnce(new Error('Network unavailable'))
      .mockRejectedValueOnce(new ApiError('Session not found', 404, null))
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [session]
    sessions.currentSessionId = 's1'
    chat.messagesBySession.s1 = [{ id: 'm1', role: 'user', content: 'hello', events: [] }]

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')
    await vi.waitFor(() => expect(sessions.error).toBe('Network unavailable'))

    expect(chat.isCancellingSession('s1')).toBe(true)
    expect(chat.messages('s1')).toHaveLength(1)

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')
    await vi.waitFor(() => expect(sessions.sessions).toEqual([]))

    expect(sessions.currentSessionId).toBe('')
    expect(sessions.error).toBe('')
    expect(chat.messagesBySession).not.toHaveProperty('s1')
    expect(chat.isCancellingSession('s1')).toBe(false)
  })

  it('does not abort session B when its deletion is rejected while deleting A', async () => {
    let resolveDelete: (() => void) | undefined
    vi.spyOn(chatApi, 'deleteSession').mockImplementation(
      () => new Promise<null>((resolve) => { resolveDelete = () => resolve(null) })
    )
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [
      session,
      { ...session, id: 's2', title: 'Session 2' }
    ]
    sessions.currentSessionId = 's1'
    const abort = vi.spyOn(chat, 'abortSession')

    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's1')
    await Promise.resolve()
    wrapper.findComponent(SessionSidebar).vm.$emit('delete', 's2')
    await Promise.resolve()

    expect(abort).toHaveBeenCalledTimes(1)
    expect(abort).toHaveBeenCalledWith('s1')
    expect(chat.isCancellingSession('s2')).toBe(false)

    resolveDelete?.()
    await vi.waitFor(() => expect(sessions.sessions.map((item) => item.id)).toEqual(['s2']))
  })

  it('disables tool confirmation controls while the session is cancelling', async () => {
    const chat = useChatStore()
    const event = {
      id: 'event-1',
      type: 'permission_required' as const,
      confirmationId: 'confirmation-1',
      kind: 'USER_CONFIRM',
      toolCalls: [{ toolCallId: 'call-1', toolName: 'shell', toolInput: {} }],
      decisions: { 'call-1': true }
    }
    chat.abortSession('s1')

    const wrapper = shallowMount(ToolEventCard, {
      props: { event, sessionId: 's1', messageId: 'message-1' },
      global: {
        stubs: {
          ElButton: {
            name: 'ElButton',
            props: ['disabled'],
            template: '<button :disabled="disabled"><slot /></button>'
          }
        }
      }
    })

    const buttons = wrapper.findAllComponents({ name: 'ElButton' })
    expect(buttons).toHaveLength(3)
    expect(buttons.every((button) => button.props('disabled') === true)).toBe(true)
  })
})

describe('SessionSidebar deletion controls', () => {
  it('disables every delete button while one session is being deleted', () => {
    const wrapper = shallowMount(SessionSidebar, {
      props: {
        sessions: [
          session,
          { ...session, id: 's2', title: 'Session 2' }
        ],
        currentSessionId: 's1',
        loading: false,
        deletingSessionId: 's1',
        cancellingSessionIds: {}
      },
      global: {
        stubs: {
          ElButton: {
            name: 'ElButton',
            props: ['disabled', 'loading'],
            template: '<button :disabled="disabled"><slot /></button>'
          }
        }
      }
    })

    const deleteButtons = wrapper.findAllComponents({ name: 'ElButton' })
    expect(deleteButtons).toHaveLength(3)
    expect(deleteButtons.slice(1).every((button) => button.props('disabled') === true)).toBe(true)
  })
})
