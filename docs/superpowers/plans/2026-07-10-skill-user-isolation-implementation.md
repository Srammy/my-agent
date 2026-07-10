# Skill User Isolation + WorkspaceSkillRepository — Implementation Plan

**Spec:** `docs/superpowers/specs/2026-07-10-skill-user-isolation-design.md` (commit 5658ad8)

---

## Task 1 — Simplify SkillDto, delete SkillFileDto and SkillCreateRequest

**Goal:** `SkillDto` shrinks to `(String name, String description)`. `SkillFileDto` and `SkillCreateRequest` are deleted along with all methods that use them.

**Files:**
- `backend/src/main/java/com/example/myagent/skill/SkillDto.java` — modify
- `backend/src/main/java/com/example/myagent/skill/SkillFileDto.java` — delete
- `backend/src/main/java/com/example/myagent/skill/SkillCreateRequest.java` — delete
- `backend/src/test/java/com/example/myagent/skill/SkillControllerTest.java` — update SKILL constant

### Step 1.1 — Update SkillControllerTest (will fail to compile until Step 1.2)

Change the `SKILL` constant:
```java
private static final SkillDto SKILL = new SkillDto("java-helper", "Java helper");
```
Remove `jsonPath("$[0].editable")` and `jsonPath("$[0].updatedAt")` assertions from `getMineReturnsWorkspaceSkills`.
Remove the `putFileAcceptsNestedSkillPath` test entirely (endpoint is being deleted).

### Step 1.2 — Simplify SkillDto.java

```java
package com.example.myagent.skill;

public record SkillDto(String name, String description) {}
```

### Step 1.3 — Delete SkillFileDto.java and SkillCreateRequest.java

Delete both files. These are replaced entirely in Task 2 and 3.

### Step 1.4 — Commit

```
refactor: simplify SkillDto to name+description, delete SkillFileDto/SkillCreateRequest
```

---

## Task 2 — AgentScopeConfig + rewrite AgentScopeWorkspaceService

**Goal:**
1. `workspaceFilesystem` bean adds `IsolationScope.USER.toNamespaceFactory()` — keys stored as `userId/skills/…`.
2. `AgentScopeWorkspaceService` rewritten with `WorkspaceSkillRepository`; `createSkill` accepts `List<Part>` (multipart upload).

**Files:**
- `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- `backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java`
- `backend/src/test/java/com/example/myagent/skill/AgentScopeWorkspaceServiceTest.java`
- `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

### Step 2.1 — Rewrite AgentScopeWorkspaceServiceTest.java (fails until Step 2.4)

```java
package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.MockPart;
import org.springframework.web.server.ResponseStatusException;

class AgentScopeWorkspaceServiceTest {

  private static final CurrentUser ALICE = new CurrentUser(1L, "alice", "USER");
  private static final CurrentUser BOB   = new CurrentUser(2L, "bob",   "USER");

  private AgentScopeWorkspaceService service;

  @BeforeEach
  void setUp() {
    AbstractFilesystem filesystem =
        new RemoteFilesystem(new InMemoryStore(), IsolationScope.USER.toNamespaceFactory());
    service = new AgentScopeWorkspaceService(filesystem);
  }

  @Test
  void listSkillsReturnsEmptyForNewUser() {
    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void createSkillAppearsInList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    List<SkillDto> skills = service.listSkills(ALICE);
    assertThat(skills).hasSize(1);
    assertThat(skills.get(0).name()).isEqualTo("java-helper");
    assertThat(skills.get(0).description()).isEqualTo("Java helper");
  }

  @Test
  void usersAreIsolated() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThat(service.listSkills(BOB)).isEmpty();
  }

  @Test
  void deleteSkillRemovesFromList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));
    service.deleteSkill(ALICE, "java-helper");

    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void deleteNonExistentSkillThrows404() {
    assertThatThrownBy(() -> service.deleteSkill(ALICE, "missing"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createSkillRejectsMissingSkillMd() {
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createSkillRejectsInvalidSkillMd() {
    FilePart badPart = fakeFilePart("SKILL.md", "---\ndescription: no name here\n---\n");
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of(badPart)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createDuplicateSkillThrows409() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThatThrownBy(
            () -> service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }

  private static FilePart skillMdPart(String name, String description) {
    String content = "---\nname: " + name + "\ndescription: " + description + "\n---\n";
    return fakeFilePart("SKILL.md", content);
  }

  private static FilePart fakeFilePart(String filename, String content) {
    return (FilePart) new MockPart(filename, filename, content.getBytes(StandardCharsets.UTF_8));
  }
}
```

### Step 2.2 — Add isolation assertion to AgentScopeConfigTest.java

```java
@Test
void workspaceFilesystemIsolatesUsersByNamespace() {
    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
    io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem fs =
        new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, io.agentscope.harness.agent.IsolationScope.USER.toNamespaceFactory());

    io.agentscope.core.agent.RuntimeContext alice =
        io.agentscope.core.agent.RuntimeContext.builder().userId("1").sessionId("s").build();
    io.agentscope.core.agent.RuntimeContext bob =
        io.agentscope.core.agent.RuntimeContext.builder().userId("2").sessionId("s").build();

    fs.write(alice, "skills/test/SKILL.md", "---\nname: test\ndescription: t\n---\n");

    assertThat(fs.exists(bob, "skills/test/SKILL.md")).isFalse();
}
```

### Step 2.3 — Modify AgentScopeConfig.workspaceFilesystem bean

Current (line 90-94):
```java
return new RemoteFilesystem(buildBaseStore(agentProperties, redisTemplateProvider));
```

Change to:
```java
return new RemoteFilesystem(
    buildBaseStore(agentProperties, redisTemplateProvider),
    IsolationScope.USER.toNamespaceFactory());
```

### Step 2.4 — Rewrite AgentScopeWorkspaceService.java

```java
package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.skill.workspace.AgentSkill;
import io.agentscope.harness.agent.skill.workspace.WorkspaceSkillRepository;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentScopeWorkspaceService {

  private static final String SKILLS_DIR = "skills";

  private final AbstractFilesystem filesystem;

  public AgentScopeWorkspaceService(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public List<SkillDto> listSkills(CurrentUser user) {
    return repoFor(user).list().stream()
        .map(skill -> new SkillDto(skill.name(), skill.description()))
        .sorted(Comparator.comparing(SkillDto::name))
        .toList();
  }

  public SkillDto createSkill(CurrentUser user, List<Part> parts) {
    Map<String, byte[]> files = collectParts(parts);

    byte[] skillMdBytes = files.get("SKILL.md");
    if (skillMdBytes == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md is required");
    }

    String skillMdContent = new String(skillMdBytes, StandardCharsets.UTF_8);
    SkillValidator.SkillMarkdownMetadata meta = SkillValidator.validateSkillMarkdown(skillMdContent);

    String name = validateSkillName(meta.name());
    WorkspaceSkillRepository repo = repoFor(user);
    if (repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists: " + name);
    }

    Map<String, String> resources = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      if ("SKILL.md".equals(entry.getKey())) {
        continue;
      }
      String validatedPath = validateFilePath(entry.getKey());
      resources.put(validatedPath, new String(entry.getValue(), StandardCharsets.UTF_8));
    }

    AgentSkill skill = AgentSkill.builder()
        .name(name)
        .description(meta.description())
        .skillContent(skillMdContent)
        .resources(resources)
        .build();
    repo.save(List.of(skill), false);
    return new SkillDto(name, meta.description());
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    String name = validateSkillName(skillName);
    WorkspaceSkillRepository repo = repoFor(user);
    if (!repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    repo.delete(name);
  }

  private WorkspaceSkillRepository repoFor(CurrentUser user) {
    return new WorkspaceSkillRepository(filesystem, SKILLS_DIR, () -> runtimeContext(user));
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder()
        .userId(user.id().toString())
        .sessionId("workspace-api")
        .build();
  }

  private static Map<String, byte[]> collectParts(List<Part> parts) {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Part part : parts) {
      if (part instanceof FilePart filePart) {
        String filename = filePart.filename();
        if (StringUtils.hasText(filename)) {
          byte[] content = filePart.content()
              .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
              })
              .reduce(new byte[0], (a, b) -> {
                byte[] merged = new byte[a.length + b.length];
                System.arraycopy(a, 0, merged, 0, a.length);
                System.arraycopy(b, 0, merged, a.length, b.length);
                return merged;
              })
              .block();
          result.put(filename, content != null ? content : new byte[0]);
        }
      }
    }
    return result;
  }

  private static String validateSkillName(String name) {
    try {
      return SkillPathValidator.validateSkillName(name);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  private static String validateFilePath(String path) {
    try {
      return SkillPathValidator.validateFilePath(path);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }
}
```

### Step 2.5 — Verify and commit

```
mvn -pl backend test -Dtest=AgentScopeWorkspaceServiceTest,AgentScopeConfigTest
```
```
feat: user isolation via NamespaceFactory, rewrite AgentScopeWorkspaceService with WorkspaceSkillRepository
```

---

## Task 3 — SkillController: multipart POST, remove file endpoints

**Goal:** `POST /api/skills/mine` accepts `Flux<Part>` (multipart). Remove `updateMine`, `listFiles`, `upsertFile`, `deleteFile`.

**Files:**
- `backend/src/main/java/com/example/myagent/skill/SkillController.java`
- `backend/src/test/java/com/example/myagent/skill/SkillControllerTest.java`

### Step 3.1 — Update SkillControllerTest: add multipart test (fails until Step 3.2)

Remove `listMineOffloadsBlockingServiceCallToBoundedElasticThread` test (it tests `SkillCreateRequest` path).
Add:

```java
@Test
void postMineAcceptsMultipartAndCreatesSkill() {
    when(workspaceService.createSkill(eq(USER), any()))
        .thenReturn(new SkillDto("java-helper", "Java helper"));

    byte[] skillMdBytes = "---\nname: java-helper\ndescription: Java helper\n---\n"
        .getBytes(StandardCharsets.UTF_8);
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("SKILL.md", new org.springframework.core.io.ByteArrayResource(skillMdBytes) {
      @Override public String getFilename() { return "SKILL.md"; }
    });

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.name").isEqualTo("java-helper")
        .jsonPath("$.description").isEqualTo("Java helper");

    verify(workspaceService).createSkill(eq(USER), any());
}
```

### Step 3.2 — Rewrite SkillController.java

Keep only `listMine`, `createMine` (multipart), `deleteMine`:

```java
package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

  private final AgentScopeWorkspaceService workspaceService;

  public SkillController(AgentScopeWorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  @GetMapping("/mine")
  public Mono<List<SkillDto>> listMine(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> workspaceService.listSkills(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(value = "/mine", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Mono<SkillDto> createMine(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestBody Flux<Part> body) {
    return body.collectList()
        .flatMap(parts ->
            Mono.fromCallable(() -> workspaceService.createSkill(currentUser, parts))
                .subscribeOn(Schedulers.boundedElastic()));
  }

  @DeleteMapping("/mine/{skillName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteMine(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName) {
    return Mono.fromRunnable(() -> workspaceService.deleteSkill(currentUser, skillName))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }
}
```

### Step 3.3 — Verify and commit

```
mvn -pl backend test -Dtest=SkillControllerTest
```
```
feat: SkillController multipart POST, remove update/file-management endpoints
```

---

## Task 4 — Frontend: api/skills.ts + stores/skills.ts

**Goal:** `Skill` interface has 2 fields. Three API functions: `listMySkills`, `uploadSkill(File[])`, `deleteMySkill`. Store has 3 actions: `loadSkills`, `uploadSkill`, `deleteSkill`.

**Files:**
- `frontend/src/api/skills.ts`
- `frontend/src/stores/skills.ts`

### Step 4.1 — Rewrite frontend/src/api/skills.ts

```typescript
import { ApiError, apiDelete, apiGet, TOKEN_KEY } from './client'

export interface Skill {
  name: string
  description: string
}

export function listMySkills(): Promise<Skill[]> {
  return apiGet<Skill[]>('/api/skills/mine')
}

export async function uploadSkill(files: File[]): Promise<Skill> {
  const formData = new FormData()
  for (const file of files) {
    formData.append(file.name, file)
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

export function deleteMySkill(skillName: string): Promise<null> {
  return apiDelete<null>(`/api/skills/mine/${encodeURIComponent(skillName)}`)
}
```

### Step 4.2 — Rewrite frontend/src/stores/skills.ts

```typescript
import { defineStore } from 'pinia'
import { deleteMySkill, listMySkills, uploadSkill, type Skill } from '../api/skills'

interface SkillsState {
  mySkills: Skill[]
  loading: boolean
  error: string
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export const useSkillsStore = defineStore('skills', {
  state: (): SkillsState => ({
    mySkills: [],
    loading: false,
    error: ''
  }),
  actions: {
    async loadSkills() {
      this.loading = true
      this.error = ''
      try {
        this.mySkills = await listMySkills()
      } catch (error) {
        this.error = errorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async uploadSkill(files: File[]) {
      this.error = ''
      try {
        const skill = await uploadSkill(files)
        this.mySkills = [...this.mySkills, skill].sort((a, b) => a.name.localeCompare(b.name))
        return skill
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    },
    async deleteSkill(skillName: string) {
      this.error = ''
      try {
        await deleteMySkill(skillName)
        this.mySkills = this.mySkills.filter((s) => s.name !== skillName)
      } catch (error) {
        this.error = errorMessage(error)
        throw error
      }
    }
  }
})
```

### Step 4.3 — Verify and commit

```
cd frontend && npx tsc --noEmit
```
```
feat: simplify frontend Skill interface and store, uploadSkill via multipart
```

---

## Task 5 — Frontend: rewrite SkillPanel, delete SkillFileTree

**Goal:** Simple panel — skill list (name + description), upload button (file picker, multi-file), delete button per row. No editor, no file tree.

**Files:**
- `frontend/src/components/SkillPanel.vue` — rewrite
- `frontend/src/components/SkillFileTree.vue` — delete

### Step 5.1 — Rewrite frontend/src/components/SkillPanel.vue

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useSkillsStore } from '../stores/skills'

const skills = useSkillsStore()
const fileInput = ref<HTMLInputElement | null>(null)

onMounted(() => {
  skills.loadSkills()
})

function triggerUpload() {
  fileInput.value?.click()
}

async function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (!files.length) return
  await skills.uploadSkill(files)
  input.value = ''
}
</script>

<template>
  <section class="assistant-panel-section" v-loading="skills.loading">
    <div class="panel-row">
      <strong>我的 Skill</strong>
      <el-button size="small" @click="triggerUpload">上传</el-button>
      <input ref="fileInput" type="file" multiple style="display: none" @change="handleFiles" />
    </div>

    <ul class="skill-list">
      <li v-for="skill in skills.mySkills" :key="skill.name" class="skill-list-item">
        <div class="skill-info">
          <span class="skill-name">{{ skill.name }}</span>
          <span class="skill-description panel-muted">{{ skill.description }}</span>
        </div>
        <el-button size="small" type="danger" @click="skills.deleteSkill(skill.name)">
          删除
        </el-button>
      </li>
    </ul>

    <p v-if="!skills.mySkills.length && !skills.loading" class="panel-muted">
      暂无 Skill，请上传包含 SKILL.md 的文件集合。
    </p>

    <p v-if="skills.error" class="panel-error">{{ skills.error }}</p>
  </section>
</template>
```

### Step 5.2 — Delete SkillFileTree.vue

Delete `frontend/src/components/SkillFileTree.vue`.
Confirm no remaining imports: search for `SkillFileTree` across `frontend/src/`.

### Step 5.3 — Verify and commit

```
cd frontend && npx tsc --noEmit
```
```
feat: rewrite SkillPanel with upload/list/delete UI, delete SkillFileTree
```

---

## Summary

| # | Task | Commit message |
|---|------|----------------|
| 1 | Simplify DTOs | `refactor: simplify SkillDto to name+description, delete SkillFileDto/SkillCreateRequest` |
| 2 | Config + Service | `feat: user isolation via NamespaceFactory, rewrite AgentScopeWorkspaceService with WorkspaceSkillRepository` |
| 3 | Controller | `feat: SkillController multipart POST, remove update/file-management endpoints` |
| 4 | Frontend API + store | `feat: simplify frontend Skill interface and store, uploadSkill via multipart` |
| 5 | Frontend UI | `feat: rewrite SkillPanel with upload/list/delete UI, delete SkillFileTree` |

**Dependencies:** Task 1 before Task 2. Tasks 2 and 4 are independent (backend vs frontend). Task 3 depends on Task 2. Task 5 depends on Task 4.

**Parallel execution option:** Agent A handles Tasks 1+2+3 (backend). Agent B handles Tasks 4+5 (frontend). Both can run concurrently.
