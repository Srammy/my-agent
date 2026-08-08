import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelInfoPanel from '../ModelInfoPanel.vue'

describe('ModelInfoPanel', () => {
  it('does not show the compatibility-layer explanation', () => {
    const wrapper = mount(ModelInfoPanel)

    expect(wrapper.text()).not.toContain('OpenAI-compatible')
    expect(wrapper.text()).toContain('工具开关')
  })
})
