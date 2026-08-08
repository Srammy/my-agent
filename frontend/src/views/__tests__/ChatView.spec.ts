import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import * as chatApi from '../../api/chat'
import { ApiError } from '../../api/client'
import SessionSidebar from '../../components/SessionSidebar.vue'
import ToolEventCard from '../../components/ToolEventCard.vue'
import { useChatStore } from '../../stores/chat'
import { useAuthStore } from '../../stores/auth'
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
  vi.spyOn(chatApi, 'listMessages').mockResolvedValue([])
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

function setDesktopViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    value: width
  })
}

function pointerEvent(type: string, pointerId: number, clientX = 0) {
  const event = new MouseEvent(type, { bubbles: true, clientX })
  Object.defineProperty(event, 'pointerId', { value: pointerId })
  return event
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

describe('ChatView header', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('places the username beside the logout action', async () => {
    const wrapper = await mountView()
    useAuthStore().user = { id: 1, username: 'haha', role: 'USER' }
    await nextTick()

    expect(wrapper.find('.chat-brand span').exists()).toBe(false)
    expect(wrapper.find('.chat-topbar__actions').text()).toContain('haha')
  })
})

describe('ChatView sidebars resizing', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    localStorage.clear()
    setDesktopViewportWidth(1400)
  })

  it('restores saved sidebar widths on the desktop workspace', async () => {
    localStorage.setItem('myagent.chat.sessionSidebarWidth', '340')
    localStorage.setItem('myagent.chat.assistantPanelWidth', '420')

    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace')

    expect(workspace.attributes('style')).toContain('--session-sidebar-width: 340px')
    expect(workspace.attributes('style')).toContain('--assistant-panel-width: 420px')
  })

  it('falls back to default widths when saved values are invalid', async () => {
    localStorage.setItem('myagent.chat.sessionSidebarWidth', '')
    localStorage.setItem('myagent.chat.assistantPanelWidth', 'not-a-number')

    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace')

    expect(workspace.attributes('style')).toContain('--session-sidebar-width: 280px')
    expect(workspace.attributes('style')).toContain('--assistant-panel-width: 360px')
  })

  it('falls back to default widths when saved values are out of range', async () => {
    localStorage.setItem('myagent.chat.sessionSidebarWidth', '421')
    localStorage.setItem('myagent.chat.assistantPanelWidth', '501')

    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace')

    expect(workspace.attributes('style')).toContain('--session-sidebar-width: 280px')
    expect(workspace.attributes('style')).toContain('--assistant-panel-width: 360px')
  })

  it('keeps the main chat area at least 420px wide when the desktop workspace narrows', async () => {
    localStorage.setItem('myagent.chat.sessionSidebarWidth', '420')
    localStorage.setItem('myagent.chat.assistantPanelWidth', '500')
    setDesktopViewportWidth(1181)

    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace').element as HTMLElement
    vi.spyOn(workspace, 'getBoundingClientRect').mockReturnValue({
      bottom: 800,
      height: 800,
      left: 0,
      right: 1181,
      top: 0,
      width: 1181,
      x: 0,
      y: 0,
      toJSON: () => ({})
    })

    window.dispatchEvent(new Event('resize'))
    await nextTick()

    const leftWidth = Number.parseFloat(workspace.style.getPropertyValue('--session-sidebar-width'))
    const rightWidth = Number.parseFloat(workspace.style.getPropertyValue('--assistant-panel-width'))
    expect(1181 - leftWidth - rightWidth - 16).toBeGreaterThanOrEqual(420)
  })

  it('persists the left sidebar width after dragging its resizer', async () => {
    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace').element as HTMLElement
    vi.spyOn(workspace, 'getBoundingClientRect').mockReturnValue({
      bottom: 800,
      height: 800,
      left: 0,
      right: 1400,
      top: 0,
      width: 1400,
      x: 0,
      y: 0,
      toJSON: () => ({})
    })

    await wrapper.find('[data-testid="session-sidebar-resizer"]').trigger('pointerdown', {
      clientX: 280
    })
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 340 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    await nextTick()

    expect(localStorage.getItem('myagent.chat.sessionSidebarWidth')).toBe('340')
    expect(wrapper.find('.chat-workspace').attributes('style')).toContain('--session-sidebar-width: 340px')
  })

  it('persists the right sidebar width after dragging its resizer', async () => {
    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace').element as HTMLElement
    vi.spyOn(workspace, 'getBoundingClientRect').mockReturnValue({
      bottom: 800,
      height: 800,
      left: 0,
      right: 1400,
      top: 0,
      width: 1400,
      x: 0,
      y: 0,
      toJSON: () => ({})
    })

    await wrapper.find('[data-testid="assistant-panel-resizer"]').trigger('pointerdown', {
      clientX: 1040
    })
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 960 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    await nextTick()

    expect(localStorage.getItem('myagent.chat.assistantPanelWidth')).toBe('440')
    expect(wrapper.find('.chat-workspace').attributes('style')).toContain('--assistant-panel-width: 440px')
  })

  it('keeps a drag owned by its initiating pointer and persists when it is cancelled', async () => {
    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace').element as HTMLElement
    vi.spyOn(workspace, 'getBoundingClientRect').mockReturnValue({
      bottom: 800,
      height: 800,
      left: 0,
      right: 1400,
      top: 0,
      width: 1400,
      x: 0,
      y: 0,
      toJSON: () => ({})
    })
    const resizer = wrapper.find('[data-testid="session-sidebar-resizer"]').element as HTMLElement
    const setPointerCapture = vi.fn()
    const releasePointerCapture = vi.fn()
    Object.assign(resizer, { setPointerCapture, releasePointerCapture })

    resizer.dispatchEvent(pointerEvent('pointerdown', 1, 280))
    window.dispatchEvent(pointerEvent('pointermove', 2, 400))
    window.dispatchEvent(pointerEvent('pointerup', 2))
    window.dispatchEvent(pointerEvent('pointermove', 1, 340))
    window.dispatchEvent(pointerEvent('pointercancel', 1))
    await nextTick()

    expect(setPointerCapture).toHaveBeenCalledWith(1)
    expect(releasePointerCapture).toHaveBeenCalledWith(1)
    expect(wrapper.find('.chat-workspace').attributes('style')).toContain('--session-sidebar-width: 340px')
    expect(localStorage.getItem('myagent.chat.sessionSidebarWidth')).toBe('340')
    expect(document.body.classList.contains('chat-resizing')).toBe(false)
  })

  it('cleans up an active drag when the component unmounts', async () => {
    const wrapper = await mountView()
    const resizer = wrapper.find('[data-testid="session-sidebar-resizer"]').element as HTMLElement
    Object.assign(resizer, { setPointerCapture: vi.fn(), releasePointerCapture: vi.fn() })

    resizer.dispatchEvent(pointerEvent('pointerdown', 1, 280))
    wrapper.unmount()
    window.dispatchEvent(pointerEvent('pointerup', 1))

    expect(document.body.classList.contains('chat-resizing')).toBe(false)
    expect(localStorage.getItem('myagent.chat.sessionSidebarWidth')).toBeNull()
  })

  it('does not start resizing from a resizer at the responsive breakpoint', async () => {
    setDesktopViewportWidth(1180)
    const wrapper = await mountView()
    const resizer = wrapper.find('[data-testid="session-sidebar-resizer"]').element as HTMLElement
    Object.assign(resizer, { setPointerCapture: vi.fn(), releasePointerCapture: vi.fn() })

    resizer.dispatchEvent(pointerEvent('pointerdown', 1, 280))
    window.dispatchEvent(pointerEvent('pointermove', 1, 340))
    window.dispatchEvent(pointerEvent('pointerup', 1))

    expect(document.body.classList.contains('chat-resizing')).toBe(false)
    expect(localStorage.getItem('myagent.chat.sessionSidebarWidth')).toBeNull()
  })

  it('limits the left sidebar to its minimum width while dragging', async () => {
    const wrapper = await mountView()
    const workspace = wrapper.find('.chat-workspace').element as HTMLElement
    vi.spyOn(workspace, 'getBoundingClientRect').mockReturnValue({
      bottom: 800,
      height: 800,
      left: 0,
      right: 1400,
      top: 0,
      width: 1400,
      x: 0,
      y: 0,
      toJSON: () => ({})
    })
    const resizer = wrapper.find('[data-testid="session-sidebar-resizer"]').element as HTMLElement
    Object.assign(resizer, { setPointerCapture: vi.fn(), releasePointerCapture: vi.fn() })

    resizer.dispatchEvent(pointerEvent('pointerdown', 1, 280))
    window.dispatchEvent(pointerEvent('pointermove', 1, 10))
    window.dispatchEvent(pointerEvent('pointerup', 1))
    await nextTick()

    expect(localStorage.getItem('myagent.chat.sessionSidebarWidth')).toBe('220')
    expect(wrapper.find('.chat-workspace').attributes('style')).toContain('--session-sidebar-width: 220px')
  })
})

describe('ChatView cross-session sending', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('disables the composer in session B while session A is streaming', async () => {
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const chat = useChatStore()
    sessions.sessions = [
      session,
      { ...session, id: 's2', title: 'Session 2' }
    ]
    sessions.currentSessionId = 's2'
    chat.loadingSessionId = 's1'

    await wrapper.vm.$nextTick()

    expect(wrapper.findComponent({ name: 'Composer' }).props('disabled')).toBe(true)
  })
})

describe('ChatView persisted messages', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('loads messages when a current session becomes active', async () => {
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const listMessages = vi.mocked(chatApi.listMessages)
    listMessages.mockResolvedValue([
      {
        id: 'm1',
        role: 'user',
        content: 'hello again',
        events: [],
        loading: false,
        createdAt: '2026-07-18T00:00:00Z',
        updatedAt: '2026-07-18T00:00:00Z'
      }
    ])

    sessions.currentSessionId = 's1'
    await wrapper.vm.$nextTick()

    await vi.waitFor(() => expect(listMessages).toHaveBeenCalledWith('s1'))
    expect(useChatStore().messages('s1')[0].content).toBe('hello again')
  })

  it('loads messages when switching sessions', async () => {
    const wrapper = await mountView()
    const sessions = useSessionsStore()
    const listMessages = vi.mocked(chatApi.listMessages)

    sessions.currentSessionId = 's1'
    await wrapper.vm.$nextTick()
    await vi.waitFor(() => expect(listMessages).toHaveBeenCalledWith('s1'))

    sessions.currentSessionId = 's2'
    await wrapper.vm.$nextTick()

    await vi.waitFor(() => expect(listMessages).toHaveBeenCalledWith('s2'))
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
        renamingSessionId: '',
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
    expect(deleteButtons).toHaveLength(5)
    expect(deleteButtons.slice(1).every((button) => button.props('disabled') === true)).toBe(true)
  })
})
