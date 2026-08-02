import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../client'
import { uploadSkill } from '../skills'

function directoryFile(path: string, content = ''): File {
  const name = path.split('/').at(-1) ?? ''
  const file = new File([content], name)
  Object.defineProperty(file, 'webkitRelativePath', { value: path })
  return file
}

function skillDirectory() {
  return [directoryFile('java-helper/SKILL.md', '---\nname: java-helper\ndescription: Java helper\n---\n')]
}

describe('uploadSkill', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('preserves paths below the selected Skill directory in multipart filenames', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('{"name":"java-helper","description":"Java helper"}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    await uploadSkill([
      directoryFile('java-helper/SKILL.md', '---\nname: java-helper\ndescription: Java helper\n---\n'),
      directoryFile('java-helper/references/guide.md'),
      directoryFile('java-helper/scripts/run.sh'),
      directoryFile('java-helper/assets/icon.png')
    ])

    const body = fetchMock.mock.calls[0][1].body as FormData
    expect(Array.from(body.entries()).map(([field, value]) => [field, (value as File).name]))
      .toEqual([
        ['SKILL.md', 'SKILL.md'],
        ['references/guide.md', 'references/guide.md'],
        ['scripts/run.sh', 'scripts/run.sh'],
        ['assets/icon.png', 'assets/icon.png']
      ])
  })

  it('uses backend message, error, or detail when upload fails', async () => {
    for (const key of ['message', 'error', 'detail'] as const) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ [key]: `${key} reason` }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' }
        })
      ))

      await expect(uploadSkill(skillDirectory())).rejects.toMatchObject({
        name: 'ApiError',
        message: `${key} reason`,
        status: 400
      } satisfies Partial<ApiError>)
    }
  })

  it('falls back to a generic upload error when response has no structured message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('plain failure', { status: 500 })
    ))

    await expect(uploadSkill(skillDirectory())).rejects.toThrow('上传失败，请稍后重试')
  })

  it('rejects files that were not selected as a directory', async () => {
    await expect(uploadSkill([new File(['content'], 'SKILL.md')]))
      .rejects.toThrow('请选择一个完整的 Skill 目录')
  })

  it('rejects files from different selected directory roots', async () => {
    await expect(uploadSkill([
      directoryFile('java-helper/SKILL.md'),
      directoryFile('python-helper/references/guide.md')
    ])).rejects.toThrow('一次只能上传一个 Skill 目录')
  })

  it('rejects a selected directory without a root SKILL.md', async () => {
    await expect(uploadSkill([
      directoryFile('java-helper/references/guide.md')
    ])).rejects.toThrow('所选目录根部必须包含 SKILL.md')
  })
})
