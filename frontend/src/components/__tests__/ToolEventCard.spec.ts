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
    kind: 'USER_CONFIRM',
    toolCalls: [
      { toolCallId: 'call-1', toolName: 'read_file', toolInput: { path: 'a.md' } },
      { toolCallId: 'call-2', toolName: 'shell_command', toolInput: { command: 'npm test' } }
    ],
    decisions: {},
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

  it('shows every tool name and JSON input in a confirmation group', () => {
    const wrapper = mountCard()

    expect(wrapper.text()).toContain('read_file')
    expect(wrapper.text()).toContain('shell_command')
    expect(wrapper.text()).toContain('"path": "a.md"')
    expect(wrapper.text()).toContain('"command": "npm test"')
  })

  it('records an allow or reject decision for an individual tool', async () => {
    const chat = useChatStore()
    const setToolDecision = vi.spyOn(chat, 'setToolDecision')
    const event = confirmationEvent()
    const wrapper = mountCard(event)
    const buttons = wrapper.findAll('button')

    expect(buttons).toHaveLength(5)
    await buttons[0].trigger('click')
    await buttons[3].trigger('click')

    expect(setToolDecision).toHaveBeenNthCalledWith(1, event, 'call-1', true)
    expect(setToolDecision).toHaveBeenNthCalledWith(2, event, 'call-2', false)
  })

  it('enables group submission only after every tool has a decision', async () => {
    const chat = useChatStore()
    const confirmTool = vi.spyOn(chat, 'confirmTool').mockResolvedValue()
    let event = confirmationEvent()
    const wrapper = mountCard(event)
    const initialButtons = wrapper.findAll('button')

    expect(initialButtons).toHaveLength(5)
    expect(initialButtons[4].attributes('disabled')).toBeDefined()

    event = confirmationEvent({ decisions: { 'call-1': true, 'call-2': false } })
    await wrapper.setProps({ event })
    const submit = wrapper.findAll('button')[4]
    expect(submit.attributes('disabled')).toBeUndefined()

    await submit.trigger('click')
    expect(confirmTool).toHaveBeenCalledWith('s_123', 'assistant-1', event)
  })

  it.each([
    ['confirming', confirmationEvent({ confirming: true }), 's_123', 'assistant-1'],
    ['consumed', confirmationEvent({ consumed: true }), 's_123', 'assistant-1'],
    ['missing session id', confirmationEvent(), '', 'assistant-1'],
    ['missing message id', confirmationEvent(), 's_123', '']
  ])('disables all confirmation controls when %s', (_reason, event, sessionId, messageId) => {
    const wrapper = mountCard(event, sessionId, messageId)

    expect(wrapper.findAll('button')).toHaveLength(5)
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
    const wrapper = mountCard(confirmationEvent({ kind: 'EXTERNAL_EXECUTION', permission: 'filesystem', toolCalls: undefined }))

    expect(wrapper.text()).toContain('filesystem')
    expect(wrapper.find('pre').exists()).toBe(false)
  })
})
