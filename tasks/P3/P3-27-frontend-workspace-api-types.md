---
id: P3-27
title: 前端 pnpm 工作区与共享 API 类型包
phase: P3
module: root, packages/api-types
type: impl
size: S
work_type: frontend-workspace
ecc_extra: []
status: todo
depends_on: [P3-23]
---

# P3-27 前端 pnpm 工作区与共享 API 类型包

## 目标
Web 端和移动端开工前，已经有一个受 CI 保护、由契约生成的 TypeScript 类型包。

## 参考
- ADR-002（前端版本）
- ADR-008、ADR-014（两端只共享类型）
- `docs/api/openapi.json`（P3-23）

## 产出
- 根目录 `package.json`（`private: true`，`packageManager` 字段固定 pnpm 版本，只包含工作区脚本）、`pnpm-workspace.yaml`（成员 `wp-web`、`wp-mobile`、`packages/*`；`wp-web`、`wp-mobile` 目录此时可以还不存在）、`.npmrc`、`.nvmrc`（Node 24）。
- `packages/api-types/`：`package.json`（名称 `@wp/api-types`，无运行时依赖，开发依赖 `openapi-typescript` 7.x、`typescript` 5.9.x、`vitest`）；脚本 `gen` 从 `../../docs/api/openapi.json` 生成 `src/schema.d.ts`；`src/index.ts` 导出 `paths`、`components` 以及常用模型的类型别名（`EntityNode`、`ClassFrame`、`ProblemDetail` 等）。
- 生成的 `src/schema.d.ts` 提交到仓库；脚本 `check` 重新生成并与已提交版本比较，不一致时失败。
- `packages/api-types/src/__tests__/types.test-d.ts`：用 Vitest 的类型测试断言几个关键类型的字段存在（例如 `EntityNode` 有 `iri`、`displayName`）。
- CI（修改 `.github/workflows/ci.yml`）：增加 Node 24 + pnpm 任务，执行 `pnpm install --frozen-lockfile` 与 `pnpm --filter @wp/api-types run check`。

## 实现要点
- 版本号只写在各自的 `package.json` 中，并提交 `pnpm-lock.yaml`。
- 不要在本卡创建 `wp-web` 或 `wp-mobile` 工程（分别由 P4、P5 的骨架卡创建）。

## ECC 技能
工作项类型：`frontend-workspace` —— 前端 pnpm 工作区与共享 API 类型包。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-27` 查看。

- 开工前加载的 skill：`contract-first`
- 提交前调用的审查 agent：`typescript-reviewer`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm install
pnpm --filter @wp/api-types run gen
pnpm --filter @wp/api-types run check
pnpm --filter @wp/api-types exec vitest run --typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 运行时 API 客户端（Web 端用 openapi-fetch，移动端用 uni.request 封装，各自在 P4 / P5 实现）
