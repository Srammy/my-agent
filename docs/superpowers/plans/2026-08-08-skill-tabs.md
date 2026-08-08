# Skill 标签合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将右侧 `Skill` 与审核入口合并为一个顶层标签，并在其中提供 `我的skill` 与 `自进化skill审核` 两个子标签。

**Architecture:** 继续使用现有 `SkillPanel` 和 `SkillReviewPanel`，只在 `ChatView.vue` 中增加一层嵌套的 Element Plus tabs。现有数据请求、Skill 操作和审核逻辑保持不变。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Vitest、Vue Test Utils。

---

### Task 1: 更新 ChatView 标签层级测试

**Files:**
- Modify: `frontend/src/views/__tests__/ChatView.spec.ts:213-226`

- [ ] **Step 1: 修改失败测试，描述目标层级**

将现有 assistant tabs 测试改为验证：

```ts
it('groups skill list and review under one Skill tab', async () => {
  const wrapper = await mountView(true)

  expect(wrapper.findAll('[data-tab-label="Skill"]')).toHaveLength(1)
  expect(wrapper.find('[data-tab-label="Skill 审核"]').exists()).toBe(false)
  expect(wrapper.find('[data-tab-label="我的skill"]').exists()).toBe(true)
  expect(wrapper.find('[data-tab-label="自进化skill审核"]').exists()).toBe(true)
})
```

- [ ] **Step 2: 运行测试确认当前实现失败**

Run from `frontend`:

```bash
npx vitest run --pool=forks --poolOptions.forks.singleFork src/views/__tests__/ChatView.spec.ts
```

Expected: FAIL because the current view still exposes `Skill` and `Skill 审核` as separate top-level panes and does not render the two requested child labels.

### Task 2: 实现嵌套 Skill tabs

**Files:**
- Modify: `frontend/src/views/ChatView.vue:310-322`

- [ ] **Step 1: 用嵌套 tabs 替换两个顶层 pane**

将 assistant panel 的标签区域调整为：

```vue
<el-tabs class="assistant-tabs" stretch>
  <el-tab-pane label="Skill">
    <el-tabs class="skill-tabs" stretch>
      <el-tab-pane label="我的skill">
        <SkillPanel />
      </el-tab-pane>
      <el-tab-pane label="自进化skill审核" name="skillReview">
        <SkillReviewPanel />
      </el-tab-pane>
    </el-tabs>
  </el-tab-pane>
</el-tabs>
```

- [ ] **Step 2: 运行目标测试确认通过**

Run:

```bash
npx vitest run --pool=forks --poolOptions.forks.singleFork src/views/__tests__/ChatView.spec.ts
```

Expected: PASS, including the updated assistant tabs test and all existing ChatView tests.

### Task 3: 全量验证并提交

**Files:**
- No additional files.

- [ ] **Step 1: 运行前端全量测试**

```bash
npm test -- --pool=forks --poolOptions.forks.singleFork
```

Expected: all frontend test files pass.

- [ ] **Step 2: 运行类型检查和生产构建**

```bash
npm run build
```

Expected: `vue-tsc` and Vite build pass.

- [ ] **Step 3: 提交实现**

```bash
git add frontend/src/views/ChatView.vue frontend/src/views/__tests__/ChatView.spec.ts
git commit -m "feat: group skill tabs"
```

- [ ] **Step 4: 使用合并后的主分支重建前端并验证服务**

```bash
docker compose -p myagent --env-file .env up -d --build frontend
docker compose -p myagent --env-file .env ps
```

Expected: frontend and backend containers are running; frontend is available at `http://localhost:5173/chat`.
