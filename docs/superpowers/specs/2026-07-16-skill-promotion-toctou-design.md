# Skill 晋升 TOCTOU 防护设计

## 背景

当前 `WebApprovalGate` 在返回 `PromotionDecision.Approve` 前会重新计算
`skills/_drafts/<skillName>` 的 SHA-256 指纹。AgentScope 随后才调用
`WorkspaceManager.moveSkill(...)`，把草稿目录移动到正式 Skill 目录。

校验和移动是两个独立步骤。若草稿在 Gate 返回批准之后、目录移动之前被修改，
移动操作可能晋升没有被审批过的内容。这是一个典型的检查时与使用时不一致
（TOCTOU）问题。

当前 AgentScope 2.0.0-RC4 的 `SkillPromoter` 没有向应用暴露“校验并移动”的事务接口：

1. `SkillPromotionGate.review(...)` 先返回决定；
2. `SkillPromoter.applyDecision(...)` 再调用 `WorkspaceManager.moveSkill(...)`；
3. `WorkspaceManager` 最终调用应用注入的 `AbstractFilesystem.move(...)`。

因此，应用能够控制的最终安全边界是 `AbstractFilesystem.move(...)`。

## 目标

- 已批准草稿只有在移动瞬间仍与审批指纹一致时才能晋升。
- 草稿指纹复核与实际移动之间不能插入本应用发起的草稿写操作。
- 最终审批决定的读取与移动之间不能插入新的批准或拒绝决定。
- 防护在共享 Redis 的多应用实例之间生效，而不只在单个 JVM 内生效。
- 保持 Skill 按用户隔离、同一用户跨会话共享的现有语义。
- 不复制或替换 AgentScope 的 `SkillPromoter`，避免绑定其内部实现。
- 不改变前端接口、审批 JSON 结构和正式 Skill 的存储路径。

## 非目标与边界

- 不防护绕过应用、直接修改 Redis 底层键值的操作。
- 不建设审批历史或不可变审批快照系统。
- 不把整个工作区的普通文件操作串行化。
- 不修改 AgentScope 依赖源码。
- 不在本次修改中处理 `SkillDraftFingerprint` 的通用异常封装；该问题在后续独立分支处理。

## 方案选择

### 方案一：共享存储锁与移动边界复核（采用）

为 AgentScope 使用的用户固定文件系统增加一个保护层：所有影响
`skills/_drafts` 的写、编辑、删除、上传和移动操作都先取得该用户的分布式草稿锁。
当保护层识别到标准晋升移动
`skills/_drafts/<skillName> -> skills/<skillName>` 时，在同一临界区内重新读取审批决定、
重新计算草稿指纹，然后才调用底层 `move`。

优点：改动集中在应用已有的文件系统注入点；无需修改 AgentScope；多实例共享同一把锁。
代价：同一用户的草稿变更会短暂串行化；依赖所有草稿变更都通过该保护层。

### 方案二：不可变审批快照（不采用）

批准时复制草稿并保存不可变快照，晋升时移动快照而不是实时草稿。该方案能直接绑定被移动的内容，
但需要引入快照路径、创建一致性、过期清理、重复晋升和实时草稿清理规则，明显扩大本次修复范围。

### 方案三：复制 AgentScope `SkillPromoter`（不采用）

在项目内维护修改后的晋升器，把上下文、校验和移动放入一个自定义流程。该方案会复制上游内部逻辑，
升级 AgentScope 时容易产生行为漂移。

## 核心组件

### `SkillDraftLock`

新增一个基于共享 `BaseStore` 的用户级分布式锁。锁使用独立命名空间，避免出现在用户工作区文件列表中：

```text
namespace = [userId, "_skill-draft-lock"]
key       = "mutation"
```

锁记录包含随机所有者令牌和过期时间。获取与抢占过期锁使用
`BaseStore.putIfVersion(...)`，释放时先核对所有者令牌，再以版本 CAS 把记录标记为已释放，
不能删除或覆盖其他实例后来取得的锁。

锁采用有限等待并在失败时关闭操作：无法取得锁时，草稿变更或晋升失败，不能绕过保护直接执行。
租约时长必须覆盖一次完整指纹计算和目录移动；持锁代码在真正移动前再次确认锁仍由当前令牌持有。
这是一种协作式分布式锁：本应用所有 Agent 草稿写入均经由同一保护层，直接操作 Redis 不在保证范围内。

### `SkillApprovalGuardedFilesystem`

新增 `AbstractFilesystem` 装饰器，内部委托用户固定命名空间的 `RemoteFilesystem`：

- `ls`、`read`、`grep`、`glob`、`downloadFiles` 和 `exists` 直接委托；
- `write`、`edit`、`delete`、`uploadFiles` 若影响 `skills/_drafts`，在用户草稿锁内委托；
- 普通工作区路径保持原行为，不获取草稿锁；
- `move` 若源路径或目标路径影响草稿目录，在用户草稿锁内执行；
- `move` 若精确匹配标准 Skill 晋升路径，调用最终审批校验后才允许委托。

路径识别统一去除前导 `/`、统一 `\\` 为 `/`，只接受直接子目录形式的 Skill 名称，
并使用现有 `SkillPathValidator` 校验名称。

### `SkillPromotionGuard`

该组件负责最终授权，不负责普通文件系统转发。输入包括固定的 `userId`、Skill 名称和用于读取草稿的
用户文件系统，处理顺序如下：

1. 按 `userId + skillName` 读取最新 `SkillReviewDecision`；
2. 没有决定、`draftHash == null` 或状态不是 `APPROVED` 时拒绝移动；
3. 在仍持有草稿锁时重新计算完整草稿指纹；
4. 当前指纹与 `decision.draftHash` 不一致时拒绝移动；
5. 再确认分布式锁仍由当前操作持有；
6. 仅在以上条件全部满足时调用底层文件系统的 `move`。

拒绝晋升通过失败的 `WriteResult` 返回。AgentScope 会把本次晋升记为失败，草稿保持原位，等待重新审批或重试。

### `SkillReviewService`

`approve` 和 `reject` 也使用同一个用户草稿锁，并把以下步骤放在一个临界区中：

1. 计算当前完整草稿指纹；
2. 保存与该指纹绑定的批准或拒绝决定。

这样既不会在计算 hash 后、保存决定前插入草稿写入，也不会在晋升读取决定后、实际移动前插入新的审批决定。
列表查询只展示瞬时状态，不参与授权，可以继续无锁读取。

## 依赖装配

`workspaceFilesystem` 继续使用动态的 `IsolationScope.USER` 视图，供显式传入
`RuntimeContext` 的 Web/API 服务使用。

`UserScopedFilesystemFactory.create(userId)` 改为缓存并返回
`SkillApprovalGuardedFilesystem(RemoteFilesystem(store, [userId]))`。因此：

- AgentScope 即使传入 `RuntimeContext.empty()`，仍访问构造时绑定的用户命名空间；
- 同一用户跨会话复用相同受保护文件系统；
- 不同用户使用不同锁命名空间和文件命名空间；
- 两种视图仍共享同一个 `BaseStore` 和同一份 Redis 数据。

`SkillDraftLock` 作为共享 Bean 注入 `UserScopedFilesystemFactory` 和 `SkillReviewService`。
即使不同应用实例持有不同 Java 对象，它们仍通过同一个 `BaseStore` 命名空间竞争同一把用户锁。

## 并发流程

正常晋升：

```text
Gate 校验 hash
    -> AgentScope 调用 move(draft, skill)
    -> 受保护文件系统取得用户草稿锁
    -> 读取最新 APPROVED 决定
    -> 在锁内重新计算 hash
    -> hash 相同且锁仍有效
    -> 底层 RemoteFilesystem.move
    -> 释放锁
```

并发修改：

```text
晋升线程取得锁
    -> 写线程等待
    -> 晋升线程复核并移动已审批内容
    -> 释放锁
    -> 写线程继续；若仍写原草稿路径，则形成新的待审批草稿
```

若写线程先取得锁并修改草稿，晋升线程随后计算出的 hash 与审批记录不一致，移动被拒绝。

审批或拒绝流程：

```text
HTTP 审批线程取得用户草稿锁
    -> 计算实时草稿 hash
    -> 保存与 hash 绑定的决定
    -> 释放锁
```

因此，晋升临界区执行期间不能把已读取的批准决定替换为拒绝或另一版本的批准。

## 错误处理

- 锁获取超时：文件系统操作返回失败或抛出统一的锁异常，不允许无锁降级。
- 审批或拒绝无法取得锁：HTTP 操作失败且不保存决定。
- 审批不存在、已拒绝、旧记录无 hash：晋升移动返回失败。
- 指纹读取失败：晋升移动返回失败，保持草稿。
- hash 不匹配：晋升移动返回失败，保持草稿并等待重新审批。
- 底层移动失败：原样返回底层 `WriteResult`。
- 释放锁失败：记录错误；所有者令牌校验保证不会释放其他实例的锁。

## 测试策略

### 分布式锁

- 两个锁实例共享同一 `BaseStore` 时，同一用户的临界区不能并发进入。
- 不同用户的锁互不阻塞。
- 非所有者不能释放当前锁。
- 获取超时必须失败关闭。

### 受保护文件系统

- 普通文件写入直接委托，不获取草稿锁。
- 草稿写入、编辑、删除、上传和移动在锁内委托。
- 匹配审批指纹的标准晋升移动成功。
- Gate 校验后草稿被修改时，最终移动被拒绝，正式 Skill 不存在，草稿仍保留。
- 最新决定为拒绝或无决定时，标准晋升移动被拒绝。
- 晋升持锁期间，新的批准或拒绝操作必须等待；决定不能在最终复核和移动之间被替换。
- 用户 A 的审批和锁不能授权或阻塞用户 B 的同名 Skill。

### 回归验证

- `UserScopedFilesystemFactoryTest` 验证缓存、用户隔离和跨会话共享语义不变。
- `AgentScopeConfigTest` 验证 HarnessAgent 仍注入用户固定的受保护文件系统。
- Skill 相关测试和后端完整测试全部通过。

## 成功标准

- 测试能够稳定复现“Gate 批准后修改草稿，原实现仍执行移动”的问题。
- 修复后，最终移动只在锁内重新校验得到同一审批指纹时成功。
- 同一共享 `BaseStore` 上的两个应用侧文件系统实例不能在晋升临界区内并发修改同一用户草稿。
- 用户隔离、跨会话共享、普通工作区文件操作均无回归。
- 后端完整测试通过，且不修改用户原有 `.claude/` 内容。
