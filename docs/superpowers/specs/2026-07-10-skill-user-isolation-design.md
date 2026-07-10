# Skill 用户隔离与 WorkspaceSkillRepository 切换设计

日期：2026-07-10

## 目标

1. 将 `AgentScopeWorkspaceService` 从直接操作 `AbstractFilesystem` 改为使用 AgentScope 原生 `WorkspaceSkillRepository`。
2. 每个用户只能查看和操作自己的 skill（`IsolationScope.USER` 命名空间隔离）。
3. 简化 skill 管理功能：只保留列表、上传创建、删除三个操作。

## 非目标

- 不支持在 UI 中编辑单个 skill 文件。
- 不支持 skill 重命名/更新。
- 不修改 skill 自学习（`SkillReviewService`、`WebApprovalGate`）相关逻辑。
- 不处理 skill review 的用户隔离问题（已知问题，后续单独修复）。

## 背景

当前 `AgentScopeWorkspaceService` 直接调用 `filesystem.ls()`/`read()`/`write()` 操作原始路径，绕过了 AgentScope 的 `WorkspaceSkillRepository` 抽象。同时 `workspaceFilesystem` bean 构造时没有 `NamespaceFactory`，导致所有用户共享同一个 skill 池，用户隔离缺失。

## 方案

### 用户隔离机制

`RemoteFilesystem` 支持通过 `NamespaceFactory` 将 `RuntimeContext.userId` 作为 Redis key 前缀。加上 `IsolationScope.USER.toNamespaceFactory()` 后：

- Alice（userId=`101`）的 skill → Redis key：`101/skills/code-reviewer/SKILL.md`
- Bob（userId=`102`）的 skill → Redis key：`102/skills/code-reviewer/SKILL.md`

`SkillReviewDecisionStore` 使用 `userId="system"` → 数据落在 `system/skill-reviews/`，全局共享，行为不变。

### API

保留路径，精简端点：

```
GET    /api/skills/mine                   列出当前用户的全部 skill
POST   /api/skills/mine                   上传文件创建 skill（multipart/form-data）
DELETE /api/skills/mine/{skillName}       删除 skill（包含所有文件）
```

移除端点（不再支持）：
```
GET    /api/skills/{skillName}/files
PUT    /api/skills/{skillName}/files/{*path}
DELETE /api/skills/{skillName}/files/{*path}
PUT    /api/skills/{skillName}/enabled
```

### 文件上传格式

`POST /api/skills/mine` 使用 `multipart/form-data`：

```
files[]: SKILL.md                  （必须，frontmatter 含 name: 和 description:）
files[]: references/guide.md       （可选）
files[]: scripts/analyze.py        （可选）
files[]: assets/logo.png           （可选）
```

- `SKILL.md` 是唯一必须字段，其 YAML frontmatter 中的 `name:` 作为 skill 标识符，`description:` 作为展示和 LLM 注入描述。
- 其他文件的相对路径必须以 `references/`、`scripts/` 或 `assets/` 开头，由 `SkillPathValidator.validateFilePath()` 校验。
- `name` 字段同时作为 workspace 目录名，须通过 `SkillPathValidator.validateSkillName()` 校验。

### SKILL.md frontmatter 解析

后端读取上传的 SKILL.md 内容，用现有 `SkillValidator.validateSkillMarkdown()` 解析 frontmatter 提取 `name` 和 `description`（该方法保留，不删除）。

### 核心实现

**`workspaceFilesystem` bean（`AgentScopeConfig.java`）：**

```java
@Bean
AbstractFilesystem workspaceFilesystem(
    AgentProperties agentProperties,
    ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
  return new RemoteFilesystem(
      buildBaseStore(agentProperties, redisTemplateProvider),
      IsolationScope.USER.toNamespaceFactory());
}
```

**`AgentScopeWorkspaceService.java`（完整重写）：**

```java
@Service
public class AgentScopeWorkspaceService {

  private final AbstractFilesystem filesystem;

  public List<SkillDto> listSkills(CurrentUser user) {
    return repoFor(user).getAllSkills().stream()
        .map(s -> new SkillDto(s.getName(), s.getDescription()))
        .toList();
  }

  public SkillDto createSkill(CurrentUser user, List<FilePart> parts) {
    // 1. 收集 SKILL.md 和其他文件内容（阻塞读取，subscribeOn boundedElastic）
    // 2. 用 SkillValidator.validateSkillMarkdown() 解析 name/description
    // 3. SkillPathValidator 校验 name 和各文件路径
    // 4. 构建 AgentSkill（skillContent = SKILL.md 正文，resources = 其他文件 Map）
    // 5. repoFor(user).save(List.of(skill), false) — false = 不覆盖已有
    // 6. 返回 SkillDto(name, description)
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    SkillPathValidator.validateSkillName(skillName);
    WorkspaceSkillRepository repo = repoFor(user);
    if (!repo.skillExists(skillName)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    if (!repo.delete(skillName)) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete skill");
    }
  }

  private WorkspaceSkillRepository repoFor(CurrentUser user) {
    return new WorkspaceSkillRepository(filesystem, "skills", () -> runtimeContext(user));
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder()
        .userId(user.id().toString())
        .sessionId("workspace-api")
        .build();
  }
}
```

### DTO 变更

**`SkillDto.java`：**
```java
// 删除 updatedAt 和 editable 字段
public record SkillDto(String name, String description) {}
```

**删除的文件：**
- `SkillFileDto.java` — 无文件浏览功能
- `SkillCreateRequest.java` — 改为 multipart，不再使用 JSON body

**保留的文件：**
- `SkillValidator.java` — 仍用于解析 SKILL.md frontmatter
- `SkillPathValidator.java` — 路径安全校验

### SkillController 变更

```java
@RestController
@RequestMapping("/api/skills")
public class SkillController {

  @GetMapping("/mine")
  public Mono<List<SkillDto>> listMine(@AuthenticationPrincipal CurrentUser user) { ... }

  @PostMapping(value = "/mine", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Mono<SkillDto> createMine(
      @AuthenticationPrincipal CurrentUser user,
      @RequestPart("files") Flux<FilePart> files) { ... }

  @DeleteMapping("/mine/{skillName}")
  public Mono<Void> deleteMine(
      @AuthenticationPrincipal CurrentUser user,
      @PathVariable String skillName) { ... }
}
```

### 前端变更

**`frontend/src/api/skills.ts`：**
```typescript
export interface Skill {
  name: string
  description: string
}

export function listMySkills(): Promise<Skill[]>
export function uploadSkill(files: File[]): Promise<Skill>   // FormData multipart
export function deleteSkill(skillName: string): Promise<void>
// 移除: createSkill, updateSkill, listSkillFiles, upsertSkillFile, deleteSkillFile
```

**`frontend/src/stores/skills.ts`：** 只保留 `loadSkills`、`uploadSkill`、`deleteSkill`，移除文件操作 action。

**`frontend/src/components/SkillPanel.vue`：** 重写为简单列表 + 文件选择上传按钮 + 删除按钮，移除文件浏览树。

**删除：**
- `frontend/src/components/SkillFileTree.vue`

## 错误处理

| 情况 | HTTP 状态 |
|---|---|
| 上传时缺少 SKILL.md | `400 Bad Request` |
| SKILL.md frontmatter 非法（缺少 name/description） | `400 Bad Request` |
| skill name 不合法（含路径穿越字符等） | `400 Bad Request` |
| skill 已存在（同名） | `409 Conflict` |
| skill 不存在（删除时） | `404 Not Found` |
| workspace 写入失败 | `500 Internal Server Error`（日志含 userId 和 skillName） |

## 测试策略

后端：
- `AgentScopeWorkspaceServiceTest`：重写，用 `InMemoryStore` + `WorkspaceSkillRepository` 测试三个方法；通过不同 userId 的 RuntimeContext 验证用户隔离（alice 的 skill 对 bob 不可见）。
- `SkillControllerTest`：更新 mock，验证 multipart 上传端点、列表端点、删除端点的 HTTP 合约。
- `AgentScopeConfigTest`：验证 `workspaceFilesystem` bean 使用了 `RemoteFilesystem` 并携带 `NamespaceFactory`（非 null）。

前端：
- SkillPanel 展示 `listMySkills()` 返回的 skill 列表。
- 文件选择后触发 multipart 上传，成功后刷新列表。
- 删除后从列表移除对应 skill。

## 验收标准

- Alice 上传的 skill 只在 Alice 的 skill 列表中出现，Bob 看不到。
- 删除 skill 后，`WorkspaceSkillRepository.skillExists()` 返回 false。
- SKILL.md 缺失或 frontmatter 不合法时，后端返回 400。
- 现有 skill review 流程（`SkillReviewDecisionStore`、`SkillReviewService`）测试不受影响。
- 全量后端测试 PASS。
