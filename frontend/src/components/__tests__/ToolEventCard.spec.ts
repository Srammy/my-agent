import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ToolEventCard from '../ToolEventCard.vue'
import { useChatStore, type ToolEvent } from '../../stores/chat'

function confirmationEvent(overrides: Partial<ToolEvent> = {}): ToolEvent {
  return {
    id: 'permission-1',
    type: 'permission_required',
    confirmationId: 'confirm-1',
    toolName: 'shell',
    toolInput: { command: 'pwd' },
    kind: 'USER_CONFIRM',
    ...overrides
  }
}

function mountCard(event = confirmationEvent(), sessionId = 's_123', messageId = 'assistant-1') {
  return mount(ToolEventCard, {
    props: { event, sessionId, messageId },
    global: { plugins: [ElementPlus] }
  })
}

describe('ToolEventCard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows a user confirmation tool name, JSON input, and action buttons', () => {
    const wrapper = mountCard()

    expect(wrapper.text()).toContain('shell')
    expect(wrapper.find('pre').text()).toContain('"command": "pwd"')
    expect(wrapper.get('button').text()).toBe('允许一次')
    expect(wrapper.findAll('button')[1].text()).toBe('拒绝一次')
  })

  it('confirms or rejects through the chat store', async () => {
    const chat = useChatStore()
    const confirmTool = vi.spyOn(chat, 'confirmTool').mockResolvedValue()
    const event = confirmationEvent()
    const wrapper = mountCard(event)

    await wrapper.get('button').trigger('click')
    await wrapper.findAll('button')[1].trigger('click')

    expect(confirmTool).toHaveBeenNthCalledWith(1, 's_123', 'assistant-1', event, true)
    expect(confirmTool).toHaveBeenNthCalledWith(2, 's_123', 'assistant-1', event, false)
  })

  it.each([
    ['confirming', confirmationEvent({ confirming: true }), 's_123', 'assistant-1'],
    ['consumed', confirmationEvent({ consumed: true }), 's_123', 'assistant-1'],
    ['missing session id', confirmationEvent(), '', 'assistant-1'],
    ['missing message id', confirmationEvent(), 's_123', '']
  ])('disables both actions when %s', (_reason, event, sessionId, messageId) => {
    const wrapper = mountCard(event, sessionId, messageId)

    expect(wrapper.findAll('button')).toHaveLength(2)
    expect(wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('keeps external permissions as notices without action buttons', () => {
    const wrapper = mountCard(confirmationEvent({ kind: 'EXTERNAL_EXECUTION', permission: 'network' }))

    expect(wrapper.text()).toContain('network')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('keeps permissions without a confirmation id as notices without action buttons', () => {
    const wrapper = mountCard(confirmationEvent({ confirmationId: undefined, permission: 'filesystem' }))

    expect(wrapper.text()).toContain('filesystem')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('falls back to permission and omits an empty tool input preview', () => {
    const wrapper = mountCard(confirmationEvent({ toolName: undefined, permission: 'filesystem', toolInput: undefined }))

    expect(wrapper.text()).toContain('filesystem')
    expect(wrapper.find('pre').exists()).toBe(false)
  })
})
