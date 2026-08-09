import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createSession } from '../chat'

describe('chat session API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('sends the requested session mode while preserving the trimmed title', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: 'session-1',
          title: 'Knowledge',
          mode: 'KNOWLEDGE',
          createdAt: '2026-08-09T00:00:00Z',
          updatedAt: '2026-08-09T00:00:00Z'
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    )
    vi.stubGlobal('fetch', fetchMock)

    await createSession('  Knowledge  ', 'KNOWLEDGE')

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      title: 'Knowledge',
      mode: 'KNOWLEDGE'
    })
  })

  it('keeps the no-argument normal session call valid', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{}', {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      )
    )

    await createSession()

    const call = vi.mocked(fetch).mock.calls[0]
    expect(call).toBeDefined()
    const body = call?.[1]?.body
    expect(typeof body).toBe('string')
    expect(JSON.parse(body as string)).toEqual({ mode: 'NORMAL' })
  })
})
