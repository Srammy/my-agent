import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PermissionPanel from '../PermissionPanel.vue'

describe('PermissionPanel compact mode', () => {
  it('does not show the standalone panel hint when used in the composer', () => {
    const wrapper = mount(PermissionPanel, {
      props: { sessionId: '', compact: true },
      global: {
        stubs: {
          ElSelect: { template: '<select />' },
          ElOption: true
        }
      }
    })

    expect(wrapper.find('.panel-muted').exists()).toBe(false)
  })
})
