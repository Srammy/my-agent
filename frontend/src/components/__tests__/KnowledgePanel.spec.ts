import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgePanel from '../KnowledgePanel.vue'

const processing = {
  id: 'doc-1',
  originalFilename: 'guide.md',
  createdAt: '2026-08-09T12:18:00',
  contentType: 'text/markdown',
  sizeBytes: 12,
  status: 'PROCESSING',
  parentCount: 0,
  childCount: 0,
  chunkCount: 0,
  errorMessage: null
}

describe('KnowledgePanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('shows document status and chunk count', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...processing, status: 'READY', chunkCount: 7 }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    const wrapper = mount(KnowledgePanel, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('READY'))

    expect(wrapper.text()).toContain('Chunk 7')
    expect(wrapper.text()).toContain('上传时间 2026-08-09 12:18')
    expect(wrapper.text()).not.toContain('文档会异步解析、切分并建立检索索引。')
  })

  it('shows unknown when the stored document size is zero', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ ...processing, sizeBytes: 0, status: 'READY' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    ))

    const wrapper = mount(KnowledgePanel, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('大小未知'))

    expect(wrapper.text()).not.toContain('0 B')
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

  it('deletes a document after confirmation', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([{ ...processing, status: 'READY' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))

    const wrapper = mount(KnowledgePanel, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid="delete-document-doc-1"]')).toHaveLength(1))

    await wrapper.get('[data-testid="delete-document-doc-1"]').trigger('click')

    expect(fetchMock.mock.calls[1][0]).toBe('/api/knowledge/documents/doc-1')
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE')
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('guide.md'))
  })
})
