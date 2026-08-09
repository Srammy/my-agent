import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgePanel from '../KnowledgePanel.vue'

const processing = {
  id: 'doc-1',
  originalFilename: 'guide.md',
  contentType: 'text/markdown',
  sizeBytes: 12,
  status: 'PROCESSING',
  parentCount: 0,
  childCount: 0,
  errorMessage: null
}

describe('KnowledgePanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('shows document status and parent/child counts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...processing, status: 'READY', parentCount: 2, childCount: 7 }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    const wrapper = mount(KnowledgePanel, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('READY'))

    expect(wrapper.text()).toContain('父文档 2')
    expect(wrapper.text()).toContain('子文档 7')
  })

  it('renders failed document errors and polls processing documents', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([processing]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ ...processing, status: 'FAILED', errorMessage: '解析失败' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(KnowledgePanel, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('解析失败'), { timeout: 3000 })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('FAILED')
  })
})
