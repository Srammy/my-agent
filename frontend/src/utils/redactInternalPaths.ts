const DRAFT_PATH = /`?\/?skills\/_drafts(?:\/[A-Za-z0-9._-]+)*\/*`?/g
const SKILL_PATH = /`?\/?skills\/(?!_drafts(?:\/|`|\b))[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*\/*`?/g
const WORKSPACE_PATH = /`?\/?\.agentscope\/workspace(?:\/[A-Za-z0-9._-]+)*\/*`?/g

export function redactInternalPaths(text: string) {
  return text
    .replace(DRAFT_PATH, '草稿区域')
    .replace(SKILL_PATH, '正式 Skill 区域')
    .replace(WORKSPACE_PATH, '工作区')
}
