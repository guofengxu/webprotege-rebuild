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
- 工作区根在 `frontend/`（仓库根目录不放任何前端文件）：`frontend/package.json`（`private: true`，`packageManager` 字段固定 pnpm 版本，只包含工作区脚本）、`frontend/pnpm-workspace.yaml`（成员 `wp-web`、`packages/*`；`wp-mobile` 按下面的验证结果决定是否加入；`frontend/wp-web` 目录此时可以还不存在）、`frontend/.npmrc`、`frontend/.nvmrc`（Node 24）、`frontend/pnpm-lock.yaml`。
- `frontend/packages/api-types/`：`package.json`（名称 `@wp/api-types`，无运行时依赖，开发依赖 `openapi-typescript` 7.x、`typescript` 5.9.x、`vitest`）；脚本 `gen` 从 `../../../docs/api/openapi.json` 生成 `src/schema.d.ts`；`src/index.ts` 导出 `paths`、`components` 以及常用模型的类型别名（`EntityNode`、`ClassFrame`、`ProblemDetail` 等）。
- 生成的 `src/schema.d.ts` 提交到仓库；脚本 `check` 重新生成并与已提交版本比较，不一致时失败。
- `frontend/packages/api-types/src/__tests__/types.test-d.ts`：用 Vitest 的类型测试断言几个关键类型的字段存在（例如 `EntityNode` 有 `iri`、`displayName`）。
- uni-app 安装验证（ADR-014 风险项，结果写进执行记录）：在工作区成员路径 `frontend/wp-mobile` 下临时放一个 uni-app Vue3 CLI 官方模板的空工程（**不提交**），用本卡的 `.npmrc` 执行 `pnpm install` 与 `build:h5`。通过时把 `wp-mobile` 加入 `pnpm-workspace.yaml`；不通过时不加入，再验证一次单独安装（临时工程放自己的 `pnpm-workspace.yaml` 与 `.npmrc`，`pnpm -C frontend/wp-mobile install` 后依赖在 `frontend/wp-mobile/node_modules`），并在 `tasks/P5/EPIC.md` 第 1 项写明 wp-mobile 单独安装。验证完删除临时工程。
- CI（修改 `.github/workflows/ci.yml`，结构见 P0-05）：`changes` 任务增加输出 `frontend`（`frontend/**` 或 `docs/api/openapi.json` 有改动时为 true）；新增 `frontend` 任务（Node 24 + pnpm，`pnpm/action-setup` 的 `package_json_file` 指向 `frontend/package.json`，`actions/setup-node` 的 `cache-dependency-path` 指向 `frontend/pnpm-lock.yaml`），执行 `pnpm -C frontend install --frozen-lockfile` 与 `pnpm -C frontend --filter @wp/api-types run check`；把它加入 `ci-gate` 的 `needs`。

## 实现要点
- 版本号只写在各自的 `package.json` 中，并提交 `pnpm-lock.yaml`。
- 不要在本卡创建 `frontend/wp-web` 或 `frontend/wp-mobile` 工程（分别由 P4、P5 的骨架卡创建）。
- pnpm 的 `node-linker` 在一次工作区安装中对所有成员生效，不能只给 wp-mobile 改成 `hoisted`；不要为了 uni-app 把整个工作区改成 hoisted（wp-web 会失去严格的依赖隔离）。
- ADR-008、ADR-014 中的 `packages/api-types` 指本包，物理目录为 `frontend/packages/api-types`（见 overview 第 1 节）。

## ECC 技能
工作项类型：`frontend-workspace` —— 前端 pnpm 工作区与共享 API 类型包。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-27` 查看。

- 开工前加载的 skill：`contract-first`
- 提交前调用的审查 agent：`typescript-reviewer`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run gen
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter @wp/api-types exec vitest run --typecheck
test ! -e package.json && test ! -e pnpm-workspace.yaml && test ! -d packages
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 运行时 API 客户端（Web 端用 openapi-fetch，移动端用 uni.request 封装，各自在 P4 / P5 实现）
