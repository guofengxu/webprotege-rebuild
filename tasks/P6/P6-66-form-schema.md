---
id: P6-66
title: 表单：project_form / project_form_selector 表结构（V10）、JDBC 仓储与 Modulith form 模块壳
phase: P6
module: wp-app/form
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-43, P6-55]
---

# P6-66 表单存储与 form 模块壳

## 目标
编辑器库中有项目表单描述符与选择器两张表，可通过 JDBC 仓储按项目增删改查；Modulith 新增封闭模块 `form`（仅元数据），`web` 与 `migration` 允许依赖它。

## 参考
- `docs/01-rea/forms.md` §4；BS-16
- EPIC「表单（P6-43）」决定第 5、7 条；Flyway **V10**（接在 P6-55 的 V9 之后）
- 旧出处：`EntityFormRepositoryImpl`、`EntityFormSelectorRepositoryImpl`、`FormDescriptorRecord`
- `docs/02-architecture/overview.md` 第 2 节；`ModularityTest`

## 产出
- `backend/wp-app/src/main/resources/db/migration/V10__project_forms.sql`：
  - `project_form(project_id uuid not null, form_id uuid not null, ordinal int not null, descriptor jsonb not null, primary key (project_id, form_id))`
  - `project_form_selector(project_id uuid not null, form_id uuid not null, criteria jsonb not null, primary key (project_id, form_id))`
  - 索引：`project_form(project_id, ordinal)`
- `wprebuild.app.form` 包：`package-info` 声明 `@ApplicationModule`；`FormDescriptorRepository` / `FormSelectorRepository`（或合并接口）与 JDBC 实现：按项目列出（按 ordinal）、按 id 读写、删除描述符时级联删选择器、整表替换重编 ordinal。
- `docs/02-architecture/overview.md` 第 2 节：
  - 增加 `form`：职责「表单描述符与选择器持久化及管理命令」；允许依赖 `bus, access, shared`（不调用 `ChangePipeline`）。
  - `web`、`migration`、**`ontology`** 的允许依赖列表加入 `form`（`ontology` 仅用于加载描述符/选择器；写修订仍只在 ontology 内调 `ChangePipeline`）。
  - 在「ProjectRuntime 处理器」说明中注明：实体表单**读写**命令仍在 `ontology`（P6-69/P6-73）。
- `ModularityTest.EXPECTED` 与模块图同步；`web`/`migration`/`ontology` 的 `package-info` `allowedDependencies` 同步。
- 测试：空库迁移后表存在；往返保存含多态控件 JSON 的描述符与选择器；删除描述符后选择器消失；整表替换 ordinal 为 0..n-1。

## 实现要点
- `descriptor` / `criteria` 存 jsonb，形状对齐旧 Jackson（`type` 判别）；本卡不实现 HTTP。
- 依赖 P6-55 **只为** Flyway V9→V10 串行。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-66` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModularityTest,FormRepositoryTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST / OpenAPI、`EDIT_FORMS`、实体表单读写、迁移导入导出、内核生成器
