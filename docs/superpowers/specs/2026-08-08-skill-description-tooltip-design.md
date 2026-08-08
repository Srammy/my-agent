# Skill 描述悬浮查看设计

## 目标

在“我的 Skill”列表中保留 description 的单行省略展示；鼠标悬浮任意 description 时，显示完整内容并保留原始换行。

## 方案

使用现有的 Element Plus `el-tooltip` 包裹 description。提示内容使用专用容器并设置 `white-space: pre-wrap`，以保留换行和连续空格，同时允许长文本在合理宽度内换行。

不改变 Skill 数据接口、列表排序、上传、删除和用户隔离逻辑。名称和删除按钮不触发这项提示。

## 数据与交互

`skills.mySkills` 已提供每项的 `name` 和 `description`。组件在渲染 description 时将其同时用于列表中的省略文本和悬浮提示内容；description 为空时仍按现有空文本渲染。

## 测试

在 `SkillPanel` 组件测试中，先验证悬浮 description 后提示内容出现，并断言完整长文本和换行文本均可读取。保留已有上传提示和列表渲染测试。

## 验收标准

- 列表默认仍为单行省略，不改变行高。
- 悬浮任意 description 可查看完整内容。
- 完整内容保留换行。
- 前端组件测试、类型检查和生产构建通过。
