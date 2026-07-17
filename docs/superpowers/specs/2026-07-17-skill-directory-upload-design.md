# Skill 目录上传修复设计

## 背景

当前前端使用普通的多文件选择框，并在构造 multipart 请求时仅使用
`file.name`。浏览器不会为普通多文件选择保留目录结构，因此
`references/guide.md`、`scripts/run.sh` 等文件到达后端时只剩下
`guide.md`、`run.sh`。

后端 `AgentScopeWorkspaceService` 将 multipart 的 `filename` 作为 Skill
文件的相对路径，并且只接受根部的 `SKILL.md` 以及
`references/`、`scripts/`、`assets/` 下的文件。因此，仅修改 multipart
字段名不能解决问题；前端必须取得目录相对路径，并将它作为 multipart
文件名提交。

## 目标

- 一次选择并上传一个完整的 Skill 目录。
- 根部的 `SKILL.md` 以 `SKILL.md` 提交。
- 原样保留 `references/`、`scripts/`、`assets/` 下的相对目录层级。
- 保持现有后端接口、路径白名单和用户隔离行为不变。

## 非目标

- 不增加 ZIP 上传或后端解压能力。
- 不支持一次上传多个 Skill 目录。
- 不修改后端允许的 Skill 文件根目录。
- 不恢复已删除的前端 Skill 文件编辑器。

## 方案选择

采用浏览器目录选择能力：为隐藏的文件输入框增加 `webkitdirectory` 和
`multiple`。目录选择返回的每个 `File` 通过 `webkitRelativePath` 携带路径，
例如 `java-helper/references/java.md`。

其他方案未采用：

- `showDirectoryPicker()`：API 更现代，但兼容性和安全上下文限制更强，且需要
  额外的目录遍历代码。
- ZIP 上传：需要后端增加解压、大小限制和路径穿越防护，超出本次修复范围。

## 数据流

1. 用户点击“上传”，浏览器显示目录选择器。
2. 用户选择一个 Skill 目录，浏览器递归返回目录内文件。
3. 前端读取每个文件的 `webkitRelativePath`。
4. 前端确认所有文件具有相同的第一段目录名，并去掉该目录名：
   - `java-helper/SKILL.md` 转换为 `SKILL.md`。
   - `java-helper/references/guide.md` 转换为 `references/guide.md`。
   - `java-helper/scripts/tools/run.sh` 转换为 `scripts/tools/run.sh`。
5. 前端确认转换后的路径集合包含根部 `SKILL.md`。
6. 前端使用 `FormData.append(relativePath, file, relativePath)`。第三个参数确保
   multipart 的 `filename` 是完整相对路径，而不是 `file.name`。
7. 后端继续使用现有逻辑校验并保存 Skill。

## 错误处理

发送请求前，前端拒绝以下输入并通过现有 Store 错误展示机制显示原因：

- 文件没有 `webkitRelativePath`，说明用户没有选择完整目录。
- 文件不属于同一个所选目录根。
- 去掉根目录后没有根部 `SKILL.md`。

资源根目录是否合法仍由后端现有 `SkillPathValidator` 统一判断，避免前后端
重复维护路径白名单。

无文件时保持当前行为，不发起请求。上传成功或失败后均允许用户再次选择同一
目录；文件输入框需要被清空。

## 组件边界

- `SkillPanel.vue`：只负责打开目录选择器、取得文件列表、调用 Store，并在调用
  完成后清空输入框。
- `api/skills.ts`：负责校验和规范化目录相对路径、构造 multipart 请求。这是路径
  语义的唯一前端实现位置。
- `stores/skills.ts`：继续负责 loading/error 状态和更新 Skill 列表，不增加目录
  处理逻辑。
- 后端：不修改。

## 测试与验收

采用测试驱动方式：

1. 先新增 API 回归测试，构造带 `webkitRelativePath` 的文件并断言 multipart 中的
   文件名为 `SKILL.md`、`references/guide.md`、`scripts/run.sh`、
   `assets/icon.png`。该测试在修复前必须因路径被压平而失败。
2. 覆盖缺少根部 `SKILL.md` 和缺少目录相对路径的错误。
3. 新增或调整组件测试，确认文件输入框启用目录选择属性。
4. 运行前端聚焦测试、前端全量测试和生产构建。
5. 合并回 `codex/skill-review-draft-fingerprint` 后重新运行前端全量测试和构建。

成功标准：选择一个合法 Skill 目录后，后端收到并保存完整的允许目录层级；既有
上传、列表和删除行为不回归。
