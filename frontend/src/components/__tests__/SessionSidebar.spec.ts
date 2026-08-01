import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SessionSidebar from '../SessionSidebar.vue'
import type { ChatSession } from '../../api/chat'

function session(id: string, title = id): ChatSession {
  return {
    id,
    title,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:00Z'
  }
}

function mountSidebar() {
  return mount(SessionSidebar, {
    props: {
      sessions: [session('s1', 'Original')],
      currentSessionId: 's1',
      loading: false,
      deletingSessionId: '',
      renamingSessionId: '',
      cancellingSessionIds: {}
    },
    global: { plugins: [ElementPlus] }
  })
}

describe('SessionSidebar', () => {
  it('enters rename mode and emits rename on Enter', async () => {
    const wrapper = mountSidebar()

    await wrapper.get('[data-testid="rename-session-s1"]').trigger('click')
    const input = wrapper.get('input')
    await input.setValue('Renamed')
    await input.trigger('keyup.enter')

    expect(wrapper.emitted('rename')).toEqual([['s1', 'Renamed']])
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('cancels rename mode on Escape without emitting rename', async () => {
    const wrapper = mountSidebar()

    await wrapper.get('[data-testid="rename-session-s1"]').trigger('click')
    await wrapper.get('input').trigger('keyup.esc')

    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('does not save when the rename input blurs', async () => {
    const wrapper = mountSidebar()

    await wrapper.get('[data-testid="rename-session-s1"]').trigger('click')
    await wrapper.get('input').setValue('Renamed')
    await wrapper.get('input').trigger('blur')

    expect(wrapper.find('input').exists()).toBe(true)
    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('deletes without selecting the session', async () => {
    const wrapper = mountSidebar()

    await wrapper.get('[data-testid="delete-session-s1"]').trigger('click')

    expect(wrapper.emitted('delete')).toEqual([['s1']])
    expect(wrapper.emitted('select')).toBeUndefined()
  })
})
