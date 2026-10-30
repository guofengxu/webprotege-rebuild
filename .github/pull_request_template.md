<!--
标题 = 本卡提交的 header，例如 feat(kernel): port change list minimiser
用 python tools/task.py commit <卡号> --pr 创建时，正文会自动填好；手工创建时按下面填写。
规范见 .cursor/skills/git-naming-conventions/SKILL.md 第 6 节。
-->

## Summary
- 

## Deviation
- 无

## Test plan
- [ ] `python tools/task.py test <卡号>` 通过（验收命令 + 全量检查）

## Review
- 审查 agent：
- 人工确认：已在本地核对 `.task-runs/<卡号>.review.md`

## 评审人检查
- [ ] 改动只涉及卡片「产出」，没有「不在本卡范围」里的内容
- [ ] 每个提交都带 `Task:` 与 `Confirmed-by:` 尾注
- [ ] 没有新增卡片未提到的依赖；没有修改已接受的 ADR、规则或流程脚本（如有，已由维护者评审）

Task: PX-NN
