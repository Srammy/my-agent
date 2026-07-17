# Skill Directory Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the frontend upload one complete Skill directory while preserving `references/`, `scripts/`, and `assets/` relative paths in the multipart request.

**Architecture:** `SkillPanel.vue` uses a browser directory input and passes its recursive `File[]` unchanged to the Store. `api/skills.ts` is the only frontend location that validates `webkitRelativePath`, removes the selected root directory, and assigns the resulting Skill-relative path as the multipart filename. The backend API and Store contracts remain unchanged.

**Tech Stack:** Vue 3, TypeScript, Pinia, Element Plus, Vitest, Vue Test Utils, browser `File`/`FormData` APIs.

## Global Constraints

- Upload exactly one complete Skill directory per request.
- Require root-level `SKILL.md` after removing the selected directory name.
- Preserve all nested paths below `references/`, `scripts/`, and `assets/`.
- Do not modify the backend endpoint, backend path whitelist, user isolation, or Skill Store behavior.
- Do not add ZIP upload, `showDirectoryPicker()`, new dependencies, or unrelated frontend refactoring.
- Do not stage or modify the pre-existing `.claude/` directory.

---

### Task 1: Preserve Skill-relative paths in multipart uploads

**Files:**
- Create: `frontend/src/api/__tests__/skills.spec.ts`
- Modify: `frontend/src/api/skills.ts:12-30`

**Interfaces:**
- Consumes: browser `File.webkitRelativePath` values in the form `<selected-root>/<skill-relative-path>`.
- Produces: existing `uploadSkill(files: File[]): Promise<Skill>` behavior with multipart filenames equal to Skill-relative paths.

- [ ] **Step 1: Create the API regression tests**

Create `frontend/src/api/__tests__/skills.spec.ts` with tests for preserved paths and every client-side validation failure:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadSkill } from '../skills'

function directoryFile(path: string, content = ''): File {
  const name = path.split('/').at(-1) ?? ''
  const file = new File([content], name)
  Object.defineProperty(file, 'webkitRelativePath', { value: path })
  return file
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
```

- [ ] **Step 2: Run the regression tests and verify RED**

Run from `frontend/`:

```powershell
npm test -- src/api/__tests__/skills.spec.ts
```

Expected: the multipart test fails because current code produces flattened names such as `guide.md`; validation tests also fail because current code has no directory validation.

- [ ] **Step 3: Implement directory path normalization and multipart filenames**

Add this focused helper above `uploadSkill` in `frontend/src/api/skills.ts`, then use its result when constructing `FormData`:

```ts
interface SkillUploadEntry {
  file: File
  path: string
}

function prepareSkillUpload(files: File[]): SkillUploadEntry[] {
  let rootDirectory = ''

  const entries = files.map((file) => {
    const segments = file.webkitRelativePath.split('/')
    if (segments.length < 2 || segments.some((segment) => !segment)) {
      throw new Error('请选择一个完整的 Skill 目录')
    }

    const [currentRoot, ...relativeSegments] = segments
    if (!rootDirectory) {
      rootDirectory = currentRoot
    } else if (currentRoot !== rootDirectory) {
      throw new Error('一次只能上传一个 Skill 目录')
    }

    return { file, path: relativeSegments.join('/') }
  })

  if (!entries.some((entry) => entry.path === 'SKILL.md')) {
    throw new Error('所选目录根部必须包含 SKILL.md')
  }
  return entries
}

export async function uploadSkill(files: File[]): Promise<Skill> {
  const formData = new FormData()
  for (const entry of prepareSkillUpload(files)) {
    formData.append(entry.path, entry.file, entry.path)
  }
  const token = localStorage.getItem(TOKEN_KEY)
  const headers = new Headers()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch('/api/skills/mine', { method: 'POST', headers, body: formData })
  const data = await response.json()
  if (!response.ok) {
    const message =
      data && typeof data === 'object' && 'message' in data
        ? String((data as { message?: unknown }).message)
        : '上传失败，请稍后重试'
    throw new ApiError(message, response.status, data)
  }
  return data as Skill
}
```

Do not duplicate the backend `references`/`scripts`/`assets` whitelist in this helper.

- [ ] **Step 4: Run the API regression tests and verify GREEN**

Run from `frontend/`:

```powershell
npm test -- src/api/__tests__/skills.spec.ts
```

Expected: 4 tests pass, with no failed tests.

- [ ] **Step 5: Commit the API fix**

```powershell
git add frontend/src/api/skills.ts frontend/src/api/__tests__/skills.spec.ts
git commit -m "fix: preserve skill upload paths"
```

---

### Task 2: Select a directory from the Skill panel

**Files:**
- Create: `frontend/src/components/__tests__/SkillPanel.spec.ts`
- Modify: `frontend/src/components/SkillPanel.vue:13-31`

**Interfaces:**
- Consumes: existing `useSkillsStore().uploadSkill(files: File[])` action.
- Produces: a hidden directory input whose change event supplies every recursively selected file to the unchanged Store action.

- [ ] **Step 1: Create the component regression test**

Create `frontend/src/components/__tests__/SkillPanel.spec.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import * as skillsApi from '../../api/skills'
import SkillPanel from '../SkillPanel.vue'

vi.mock('../../api/skills', () => ({
  listMySkills: vi.fn(),
  uploadSkill: vi.fn(),
  deleteMySkill: vi.fn()
}))

describe('SkillPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(skillsApi.listMySkills).mockResolvedValue([])
    vi.mocked(skillsApi.uploadSkill).mockResolvedValue({
      name: 'java-helper',
      description: 'Java helper'
    })
  })

  it('uses a directory input for a complete Skill upload', () => {
    const wrapper = mount(SkillPanel, { global: { plugins: [ElementPlus] } })
    const input = wrapper.get('input[type="file"]')

    expect(input.attributes('multiple')).toBeDefined()
    expect(input.attributes('webkitdirectory')).toBeDefined()
  })

  it('clears the directory input after an upload failure', async () => {
    vi.mocked(skillsApi.uploadSkill).mockRejectedValueOnce(new Error('upload failed'))
    const errorHandler = vi.fn()
    const wrapper = mount(SkillPanel, {
      global: {
        plugins: [ElementPlus],
        config: { errorHandler }
      }
    })
    const input = wrapper.get('input[type="file"]')
    const files = [new File(['content'], 'SKILL.md')]
    let inputValue = 'selected-directory'
    Object.defineProperty(input.element, 'files', { configurable: true, value: files })
    Object.defineProperty(input.element, 'value', {
      configurable: true,
      get: () => inputValue,
      set: (value: string) => { inputValue = value }
    })

    await input.trigger('change')
    await vi.waitFor(() => expect(errorHandler).toHaveBeenCalled())

    expect(inputValue).toBe('')
  })
})
```

- [ ] **Step 2: Run the component test and verify RED**

Run from `frontend/`:

```powershell
npm test -- src/components/__tests__/SkillPanel.spec.ts
```

Expected: both tests fail: the existing input has no `webkitdirectory` attribute, and a rejected upload skips the existing input reset statement.

- [ ] **Step 3: Enable directory selection and reliable reselection**

Change `handleFiles` so the input is cleared even when the Store rejects the upload:

```ts
async function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (!files.length) return
  try {
    await skills.uploadSkill(files)
  } finally {
    input.value = ''
  }
}
```

Change the hidden input to directory mode:

```vue
<input
  ref="fileInput"
  type="file"
  multiple
  webkitdirectory
  style="display: none"
  @change="handleFiles"
/>
```

Do not add a second upload mode or change the Store.

- [ ] **Step 4: Run the component and API tests**

Run from `frontend/`:

```powershell
npm test -- src/components/__tests__/SkillPanel.spec.ts src/api/__tests__/skills.spec.ts
```

Expected: 6 tests pass, with no failed tests.

- [ ] **Step 5: Commit the directory picker change**

```powershell
git add frontend/src/components/SkillPanel.vue frontend/src/components/__tests__/SkillPanel.spec.ts
git commit -m "fix: select complete skill directories"
```

---

### Task 3: Verify and integrate the complete fix

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: Task 1 multipart path preservation and Task 2 directory selection.
- Produces: a tested feature branch ready for local merge into `codex/skill-review-draft-fingerprint`.

- [ ] **Step 1: Run all frontend tests**

Run from `frontend/`:

```powershell
npm test
```

Expected: every Vitest suite passes with zero failed tests.

- [ ] **Step 2: Run the production build**

Run from `frontend/`:

```powershell
npm run build
```

Expected: `vue-tsc -b && vite build` exits with code 0.

- [ ] **Step 3: Inspect only the intended branch diff**

Run from the repository root:

```powershell
git diff --check codex/skill-review-draft-fingerprint...HEAD
git diff --stat codex/skill-review-draft-fingerprint...HEAD
git status --short --branch
```

Expected: no whitespace errors; only the design/plan documents and the four planned frontend files differ; `.claude/` remains untracked and untouched.

- [ ] **Step 4: Merge locally and reverify**

From the repository root:

```powershell
git switch codex/skill-review-draft-fingerprint
git merge --no-ff codex/fix-skill-directory-upload -m "merge: support complete skill directory uploads"
cd frontend
npm test
npm run build
cd ..
```

Expected: merge succeeds; all frontend tests pass; the production build exits with code 0.

- [ ] **Step 5: Delete the fully merged temporary branch**

```powershell
git branch -d codex/fix-skill-directory-upload
```

Expected: Git confirms that the fully merged branch was deleted.
