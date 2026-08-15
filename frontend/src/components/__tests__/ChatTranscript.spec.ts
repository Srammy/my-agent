import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ChatTranscript from '../ChatTranscript.vue'
import type { ChatMessage } from '../../stores/chat'

function message(id: string, content: string): ChatMessage {
  return {
    id,
    role: 'assistant',
    content,
    events: []
  }
}

function setScrollMetrics(element: Element, metrics: {
  clientHeight: number
  scrollHeight: number
  scrollTop: number
}) {
  Object.defineProperty(element, 'clientHeight', { configurable: true, value: metrics.clientHeight })
  Object.defineProperty(element, 'scrollHeight', { configurable: true, value: metrics.scrollHeight })
  Object.defineProperty(element, 'scrollTop', { configurable: true, writable: true, value: metrics.scrollTop })
}

async function flushScroll() {
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('ChatTranscript auto scroll', () => {
  it('scrolls to the bottom when messages are added', async () => {
    const wrapper = mount(ChatTranscript, {
      props: { messages: [], loading: false, hasSession: true, sessionId: 's1' },
      global: { stubs: { ToolEventCard: true } }
    })
    const transcript = wrapper.get('.chat-transcript').element
    setScrollMetrics(transcript, { clientHeight: 100, scrollHeight: 300, scrollTop: 200 })

    await wrapper.setProps({ messages: [message('m1', 'hello')] })
    await flushScroll()

    expect(transcript.scrollTop).toBe(300)
  })

  it('keeps following streamed assistant content while near the bottom', async () => {
    const messages = [message('m1', 'hel')]
    const wrapper = mount(ChatTranscript, {
      props: { messages, loading: true, hasSession: true, sessionId: 's1' },
      global: { stubs: { ToolEventCard: true } }
    })
    const transcript = wrapper.get('.chat-transcript').element
    setScrollMetrics(transcript, { clientHeight: 100, scrollHeight: 300, scrollTop: 200 })

    await wrapper.setProps({ messages: [message('m1', 'hello world')] })
    await flushScroll()

    expect(transcript.scrollTop).toBe(300)
  })

  it('does not show a response status label while loading', () => {
    const wrapper = mount(ChatTranscript, {
      props: { messages: [message('m1', 'hello')], loading: true, hasSession: true, sessionId: 's1' },
      global: { stubs: { ToolEventCard: true } }
    })

    expect(wrapper.find('.chat-transcript__status').exists()).toBe(false)
  })

  it('hides internal paths from assistant content already held in memory', () => {
    const wrapper = mount(ChatTranscript, {
      props: {
        messages: [message('m1', '草稿目录（`skills/_drafts/`）为空。')],
        loading: false,
        hasSession: true,
        sessionId: 's1'
      },
      global: { stubs: { ToolEventCard: true } }
    })

    const content = wrapper.get('.message-bubble__content').text()

    expect(content).toContain('草稿区域')
    expect(content).not.toContain('skills/_drafts/')
  })

  it('collapses assistant references and expands them on demand', async () => {
    const wrapper = mount(ChatTranscript, {
      props: {
        messages: [message('m1', '回答内容\n\n参考来源：\n- guide.pdf，第 1 页\n- guide.pdf，第 2 页')],
        loading: false,
        hasSession: true,
        sessionId: 's1'
      },
      global: { stubs: { ToolEventCard: true } }
    })

    const references = wrapper.get('.message-references')

    expect(wrapper.get('.message-bubble__content').text()).toBe('回答内容')
    expect(references.get('summary').text()).toContain('参考来源（2 条）')
    expect(references.attributes('open')).toBeUndefined()
    expect(references.findAll('li')).toHaveLength(2)

    await references.get('summary').trigger('click')

    expect(references.attributes('open')).toBeDefined()
  })

  it('hides references when the knowledge answer cannot be determined', () => {
    const wrapper = mount(ChatTranscript, {
      props: {
        messages: [message('m1', '无法从知识库确定如何进行品牌营销。\n\n参考来源：\n- python.pdf，第 1 页')],
        loading: false,
        hasSession: true,
        sessionId: 's1'
      },
      global: { stubs: { ToolEventCard: true } }
    })

    expect(wrapper.get('.message-bubble__content').text()).toContain('无法从知识库确定')
    expect(wrapper.find('.message-references').exists()).toBe(false)
  })

  it('does not force scroll when the user has scrolled up', async () => {
    const wrapper = mount(ChatTranscript, {
      props: { messages: [message('m1', 'hello')], loading: false, hasSession: true, sessionId: 's1' },
      global: { stubs: { ToolEventCard: true } }
    })
    const transcript = wrapper.get('.chat-transcript').element
    setScrollMetrics(transcript, { clientHeight: 100, scrollHeight: 400, scrollTop: 100 })

    await wrapper.get('.chat-transcript').trigger('scroll')
    await wrapper.setProps({ messages: [message('m1', 'hello'), message('m2', 'new')] })
    await flushScroll()

    expect(transcript.scrollTop).toBe(100)
  })

  it('scrolls to the bottom when switching sessions', async () => {
    const wrapper = mount(ChatTranscript, {
      props: { messages: [message('m1', 'old')], loading: false, hasSession: true, sessionId: 's1' },
      global: { stubs: { ToolEventCard: true } }
    })
    const transcript = wrapper.get('.chat-transcript').element
    setScrollMetrics(transcript, { clientHeight: 100, scrollHeight: 500, scrollTop: 100 })

    await wrapper.setProps({ sessionId: 's2', messages: [message('m2', 'history')] })
    await flushScroll()

    expect(transcript.scrollTop).toBe(500)
  })
})
