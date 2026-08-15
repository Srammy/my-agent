import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import * as permissionApi from '../../api/permissions'
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

  it('does not show permission loading errors in the composer', async () => {
    vi.spyOn(permissionApi, 'getPermissionMode').mockRejectedValueOnce(new Error('Not Found'))

    const wrapper = mount(PermissionPanel, {
      props: { sessionId: 'missing-session', compact: true },
      global: {
        stubs: {
          ElSelect: { template: '<select />' },
          ElOption: true
        }
      }
    })

    await vi.waitFor(() => expect(permissionApi.getPermissionMode).toHaveBeenCalledWith('missing-session'))

    expect(wrapper.find('.panel-error').exists()).toBe(false)
  })
})
