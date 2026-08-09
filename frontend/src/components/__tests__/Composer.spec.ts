import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Composer from '../Composer.vue'

describe('Composer permission control', () => {
  it('shows the current conversation mode above the input', () => {
    const wrapper = mount(Composer, {
      props: { disabled: false, hasSession: true, sessionId: 's1', mode: 'KNOWLEDGE' },
      global: {
        stubs: {
          ElInput: { template: '<textarea class="composer__input" />' },
          ElButton: { template: '<button class="composer__send"><slot /></button>' },
          PermissionPanel: { template: '<div class="composer__permission" />' }
        }
      }
    })

    expect(wrapper.get('.composer__mode').text()).toContain('知识库问答')
  })

  it('places the compact permission selector between the message input and send button', () => {
    const wrapper = mount(Composer, {
      props: { disabled: false, hasSession: true, sessionId: 's1' },
      global: {
        stubs: {
          ElInput: { template: '<textarea class="composer__input" />' },
          ElButton: { template: '<button class="composer__send"><slot /></button>' },
          PermissionPanel: {
            props: ['sessionId', 'compact'],
            template: '<div class="composer__permission" :data-session-id="sessionId" :data-compact="String(compact)" />'
          }
        }
      }
    })

    const form = wrapper.get('form')
    const children = Array.from(form.element.children)

    expect(children.map((child) => child.className)).toEqual([
      'composer__input',
      'composer__permission',
      'composer__send'
    ])
    expect(wrapper.get('.composer__permission').attributes()).toMatchObject({
      'data-session-id': 's1',
      'data-compact': 'true'
    })
  })
})
