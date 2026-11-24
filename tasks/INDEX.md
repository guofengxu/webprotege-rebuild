# 任务索引

> 本文件由 `python tools/plan_lint.py` 生成并纳入 Git，请勿手工编辑；`tools/task.py finish` / `block` 会自动更新。状态以各卡片文件为准，主分支上的进度也可以运行 `python tools/task.py next` 查看。

共 198 张卡：完成 137，进行中 0，阻塞 7，待办 54。

## 现在可以开工的卡（按本地文件计算）

- [P6-50](P6/P6-50-upload-merge-apply-api.md) 后端：上传并合并到已有项目的预览、应用与放弃接口（在生成锁内校验预览时的修订号）
- [P6-57](P6/P6-57-instances-app-poc.md) PoC：新建 wp-instances-app Spring Boot 服务骨架（双数据源、Flyway、健康检查）
- [P6-65](P6/P6-65-web-workspace-spec-isolation.md) Web 端：WorkspacePage 测试与真实注册表隔离，修复 main 上 wp-web verify 的失败
- [P6-68](P6/P6-68-form-change-generator.md) 内核：实体表单数据转公理变更生成器（BS-18 首期子集 TEXT/NUMBER）

## 阻塞中

- [P5-00](P5/P5-00-mobile-planning.md) 规划卡：细化移动端（uni-app）MVP 任务卡
- [P6-39](P6/P6-39-wecom-login.md) 后端：企业微信扫码登录（复用 P6-37 的外部身份绑定与会话建立）
- [P6-40](P6/P6-40-multi-instance-planning.md) 规划卡：多实例部署（按项目路由），产出 ADR 草案与拆卡
- [P6-41](P6/P6-41-web-vue-3-6-upgrade.md) Web 端：升级到 Vue 3.6 正式版（不启用 Vapor 模式），验证生态库兼容
- [P6-47](P6/P6-47-manchester-rea-planning.md) 规划卡：Manchester 语法编辑逆向分析，确定首期范围并拆卡
- [P6-48](P6/P6-48-mcp-write-tools-planning.md) 规划卡：MCP 写工具与人工确认机制（设计、ADR 草案与拆卡）
- [P6-63](P6/P6-63-projection-constraints-planning.md) 规划卡：扩展投影表以支持必填、基数、枚举取值（ADR 草案与拆卡）

## P0

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P0-01](P0/P0-01-repo-skeleton.md) | 建立 Maven 多模块骨架与 Maven Wrapper | root | build | S | 完成 | — |
| [P0-02](P0/P0-02-dependency-smoke.md) | 依赖兼容性冒烟：OWL API 5 与 Spring Boot 4.1 共存 | wp-kernel, wp-app | build | S | 完成 | P0-01 |
| [P0-03](P0/P0-03-quality-gates.md) | 质量门禁：Spotless、Enforcer、JaCoCo | root | build | S | 完成 | P0-02 |
| [P0-04](P0/P0-04-postgres-testcontainers.md) | 本地 PostgreSQL 与 Testcontainers 测试基座 | wp-app | db | S | 完成 | P0-02 |
| [P0-05](P0/P0-05-ci.md) | GitHub Actions CI | root | ci-devops | S | 完成 | P0-03、P0-04 |
| [P0-06](P0/P0-06-test-corpus.md) | 测试本体语料与合成本体生成器 | testdata, wp-kernel | test-data | M | 完成 | P0-02 |
| [P0-07](P0/P0-07-modulith-skeleton.md) | 声明 wp-app 的 Modulith 模块与依赖规则 | wp-app | app-service | S | 完成 | P0-04 |
| [P0-08](P0/P0-08-split-source-trees.md) | 按运行时拆分源码目录 | root | build | S | 完成 | P0-02 |
| [P0-09](P0/P0-09-frontend-workspace-layout.md) | 前端工作区收进 frontend/，补齐跨端构建约定 | root | build | S | 完成 | P0-08 |

## P1

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P1-01](P1/P1-01-kernel-ids.md) | 内核标识类型：ProjectId、RevisionNumber、UserId | wp-kernel | kernel | S | 完成 | P0-03 |
| [P1-02](P1/P1-02-change-model.md) | 本体变更模型：OntologyKey 与 7 种 OntologyChange | wp-kernel | kernel | M | 完成 | P1-01、P0-06 |
| [P1-03](P1/P1-03-change-minimiser.md) | 变更列表最小化（BS-01） | wp-kernel | kernel | S | 完成 | P1-02 |
| [P1-04](P1/P1-04-project-ontologies.md) | 内存本体读模型 ProjectOntologies（OWL API 5 实现） | wp-kernel | kernel | M | 完成 | P1-02 |
| [P1-05](P1/P1-05-effective-changes.md) | 有效变更过滤（BS-02） | wp-kernel | kernel | S | 完成 | P1-03、P1-04 |
| [P1-06](P1/P1-06-ofn-line-codec.md) | 变更编码 ofn-lines/1（ADR-011） | wp-kernel | kernel | M | 完成 | P1-02、P0-06 |
| [P1-07](P1/P1-07-snapshot-codec.md) | 快照编码 ofn-zip/1 | wp-kernel | kernel | M | 完成 | P1-04 |
| [P1-08](P1/P1-08-revision-log-ports.md) | 修订与修订日志端口（含内存实现和契约测试） | wp-kernel | kernel | M | 完成 | P1-02 |
| [P1-09](P1/P1-09-project-runtime-loader.md) | ProjectRuntime 与加载器（快照 + 重放） | wp-kernel | kernel | M | 完成 | P1-05、P1-07、P1-08 |
| [P1-10](P1/P1-10-write-gate.md) | ProjectWriteGate：生成锁 + 读写锁 | wp-kernel | kernel | S | 完成 | P1-01 |
| [P1-11](P1/P1-11-change-pipeline.md) | ChangePipeline：先持久化后应用的写入流水线 | wp-kernel | kernel | M | 完成 | P1-09、P1-10 |
| [P1-12](P1/P1-12-fresh-entity-iri.md) | 临时实体 IRI 协议与 IRI 替换（BS-05） | wp-kernel | kernel | M | 完成 | P1-11 |
| [P1-13](P1/P1-13-crudkit-uuid-supplied.md) | 实体 IRI 生成：UUID 与用户输入名称两种方式（BS-06） | wp-kernel | kernel | M | 完成 | P1-12 |
| [P1-14](P1/P1-14-crudkit-obo.md) | 实体 IRI 生成：OBO 风格（BS-06） | wp-kernel | kernel | S | 完成 | P1-13 |
| [P1-15](P1/P1-15-revert-generator.md) | 回滚修订生成器（BS-04） | wp-kernel | kernel | S | 完成 | P1-11 |
| [P1-16](P1/P1-16-create-delete-generators.md) | 创建实体与删除实体生成器（BS-07） | wp-kernel | kernel | M | 完成 | P1-13、P1-05 |
| [P1-17](P1/P1-17-class-hierarchy.md) | 类层级视图（BS-08） | wp-kernel | kernel | M | 完成 | P1-09 |
| [P1-18](P1/P1-18-property-hierarchies.md) | 对象 / 数据 / 注解属性层级视图（BS-08） | wp-kernel | kernel | M | 完成 | P1-17 |
| [P1-19](P1/P1-19-display-names.md) | 显示名称视图（BS-09） | wp-kernel | kernel | M | 完成 | P1-09 |
| [P1-20](P1/P1-20-kernel-benchmark.md) | 内核性能基准与门禁 | wp-kernel | benchmark | S | 完成 | P1-11、P1-17、P1-19、P1-06、P1-22、P1-23、P1-24、P1-25、P1-26 |
| [P1-21](P1/P1-21-kernel-vertical-slice.md) | 内核竖切验收（里程碑 M1） | wp-kernel | kernel | M | 完成 | P1-15、P1-16、P1-18、P1-19、P1-06 |
| [P1-22](P1/P1-22-snapshot-import-ignore.md) | 快照编解码只忽略导入声明的 IRI（修复 P1-20 快照解码超时） | wp-kernel | kernel | S | 完成 | P1-07 |
| [P1-23](P1/P1-23-line-codec-shared-parser.md) | 逐行编解码共用解析上下文（修复 P1-20 逐行解码吞吐） | wp-kernel | kernel | S | 完成 | P1-06 |
| [P1-24](P1/P1-24-hierarchy-view-presence-check.md) | 层级视图增量更新不再物化全项目签名（修复 P1-20 编辑延迟之一） | wp-kernel | kernel | S | 完成 | P1-18 |
| [P1-25](P1/P1-25-display-name-incremental.md) | 显示名称视图增量更新（修复 P1-20 编辑延迟之二） | wp-kernel | kernel | M | 完成 | P1-19、P1-24 |
| [P1-26](P1/P1-26-line-codec-direct-parser.md) | 逐行编解码直接调用函数式语法产生式（修复 P1-20 逐行解码吞吐之二） | wp-kernel | kernel | S | 完成 | P1-23 |

## P2

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P2-01](P2/P2-01-migrator-skeleton.md) | 迁移工具骨架：OWL API 4 + BinaryOWL + CLI | wp-legacy-migrator | migrator | S | 完成 | P0-03 |
| [P2-02](P2/P2-02-binaryowl-reader.md) | 读取旧 BinaryOWL 修订日志 | wp-legacy-migrator | migrator | M | 完成 | P2-01 |
| [P2-03](P2/P2-03-interchange-writer.md) | 写出迁移包：manifest、revisions.jsonl、SHA256SUMS | wp-legacy-migrator | migrator | M | 完成 | P2-02 |
| [P2-04](P2/P2-04-mongo-metadata.md) | 读取 mongoexport 元数据并映射角色 | wp-legacy-migrator | migrator | M | 完成 | P2-03 |
| [P2-05](P2/P2-05-head-baseline-verify.md) | head 基准文件、verify 子命令与迁移报告（里程碑 M2） | wp-legacy-migrator | migrator | M | 完成 | P2-04 |

## P3

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P3-01](P3/P3-01-core-schema.md) | 核心数据库表结构（Flyway V2） | wp-app/revision, access, project | db | S | 完成 | P0-07 |
| [P3-02](P3/P3-02-jdbc-revision-log.md) | PostgreSQL 版 RevisionLog 与 SnapshotStore（含 outbox） | wp-app/revision | db | M | 完成 | P3-01、P1-08、P1-06、P1-07 |
| [P3-03](P3/P3-03-runtime-registry.md) | ProjectRuntime 注册表（Caffeine）与快照策略 | wp-app/ontology | app-service | M | 完成 | P3-02、P1-21 |
| [P3-04](P3/P3-04-users-login.md) | 用户账户、会话登录与首个管理员 | wp-app/access, web | app-security | M | 完成 | P3-01 |
| [P3-05](P3/P3-05-api-keys.md) | API Key 认证 | wp-app/access, web | app-security | S | 完成 | P3-04 |
| [P3-06](P3/P3-06-roles-permissions.md) | 角色、动作与权限判断（BS-10） | wp-app/access | app-security | M | 完成 | P3-04 |
| [P3-07](P3/P3-07-command-bus.md) | 命令总线与统一校验链 | wp-app/bus, web | app-service | M | 完成 | P3-06 |
| [P3-08](P3/P3-08-project-lifecycle.md) | 项目生命周期：创建空项目、列表、详情、回收站 | wp-app/project, ontology, web | app-service | M | 完成 | P3-07、P3-03 |
| [P3-09](P3/P3-09-upload-import.md) | 上传导入：多格式与 zip（BS-12） | wp-app/ontology, web | app-service | M | 完成 | P3-08 |
| [P3-10](P3/P3-10-migration-import-revisions.md) | 导入迁移包：修订历史与 IC 校验 | wp-app/migration | app-service | M | 完成 | P3-08、P2-05 |
| [P3-11](P3/P3-11-migration-import-metadata.md) | 导入迁移包：用户、角色、前缀与设置 | wp-app/migration | app-service | S | 完成 | P3-10、P3-06 |
| [P3-12](P3/P3-12-download-export.md) | 下载导出：任意修订、多格式（BS-12） | wp-app/ontology, web | app-service | M | 完成 | P3-08 |
| [P3-13](P3/P3-13-hierarchy-queries.md) | 层级查询 API | wp-app/ontology, web | app-service | M | 完成 | P3-08 |
| [P3-14](P3/P3-14-entity-create-delete.md) | 创建与删除实体 API | wp-app/ontology, web | app-service | S | 完成 | P3-13 |
| [P3-15](P3/P3-15-class-frame-read.md) | 类 Frame 读取 | wp-app/ontology, web | app-service | M | 完成 | P3-13 |
| [P3-16](P3/P3-16-class-frame-update.md) | 类 Frame 更新（含乐观并发） | wp-app/ontology, web | app-service | M | 完成 | P3-15、P3-14 |
| [P3-17](P3/P3-17-property-frames.md) | 对象 / 数据 / 注解属性 Frame 的读取与更新 | wp-app/ontology, web | app-service | M | 完成 | P3-16 |
| [P3-18](P3/P3-18-individual-frame.md) | 个体 Frame 的读取与更新 | wp-app/ontology, web | app-service | S | 完成 | P3-17 |
| [P3-19](P3/P3-19-search.md) | 实体搜索（Lucene 10） | wp-app/search, web | app-service | M | 完成 | P3-13 |
| [P3-20](P3/P3-20-revision-history.md) | 修订历史、实体历史与回滚 API | wp-app/revision, ontology, web | app-service | M | 完成 | P3-08、P1-15 |
| [P3-21](P3/P3-21-events-sse.md) | 高层事件推送：SSE 与长轮询（BS-11） | wp-app/events, web | app-service | M | 完成 | P3-03、P3-06 |
| [P3-22](P3/P3-22-project-settings-sharing.md) | 项目设置与共享（角色分配）API | wp-app/project, ontology, access, web | app-service | M | 完成 | P3-14、P3-06 |
| [P3-23](P3/P3-23-openapi-contract.md) | OpenAPI 契约快照与 TypeScript 客户端生成 | wp-app/web | api-contract | S | 完成 | P3-09、P3-10、P3-11、P3-12、P3-17、P3-18、P3-19、P3-20、P3-21、P3-22、P3-25、P3-26 |
| [P3-24](P3/P3-24-backend-acceptance.md) | 后端端到端验收（里程碑 M3） | wp-app | app-service | S | 完成 | P3-23 |
| [P3-25](P3/P3-25-admin-user-management.md) | 管理员的用户管理 API | wp-app/web（admin 子包） | app-security | S | 完成 | P3-07 |
| [P3-26](P3/P3-26-mobile-access-tokens.md) | 移动端访问令牌与 CORS | wp-app/access, web | app-security | M | 完成 | P3-05 |
| [P3-27](P3/P3-27-frontend-workspace-api-types.md) | 前端 pnpm 工作区与共享 API 类型包 | root, packages/api-types | frontend-workspace | S | 完成 | P3-23 |

## P4

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P4-00](P4/P4-00-web-frontend-planning.md) | 规划卡：细化 Web 端（Vue）MVP 任务卡 | tasks/P4 | planning | S | 完成 | P3-24、P3-27 |
| [P4-01](P4/P4-01-web-skeleton.md) | wp-web 工程骨架：Vue 3.5 + Vite 8 + TypeScript strict + ESLint + Vitest + Playwright | wp-web | web | S | 完成 | P3-27 |
| [P4-02](P4/P4-02-api-client-auth.md) | API 客户端、CSRF、ProblemDetail 错误处理、查询键、Vue Query 全局配置与测试支持 | wp-web | web | M | 完成 | P4-01 |
| [P4-03](P4/P4-03-layout-theme-i18n.md) | 主布局与顶部栏、Element Plus 主题与设计令牌、zh-CN / en-US 界面语言切换 | wp-web | web | M | 完成 | P4-25 |
| [P4-04](P4/P4-04-project-list.md) | 项目列表：新建空项目、上传导入（进度与错误展示）、回收站 | wp-web | web | M | 完成 | P4-03 |
| [P4-05](P4/P4-05-project-workspace.md) | 项目工作台：项目外壳路由、左侧层级面板与右侧详情面板、URL 保存选中状态 | wp-web | web | M | 完成 | P4-03 |
| [P4-06](P4/P4-06-class-hierarchy-tree.md) | 类层级树：el-tree-v2 虚拟滚动、按需加载子节点、通过 paths 定位选中实体 | wp-web | web | M | 完成 | P4-05、P4-18 |
| [P4-07](P4/P4-07-property-hierarchy-trees.md) | 对象 / 数据 / 注解属性层级树（复用类层级树组件） | wp-web | web | S | 完成 | P4-06 |
| [P4-08](P4/P4-08-individual-list.md) | 个体列表：在类树中选类，分页列出该类的直接个体 | wp-web | web | S | 完成 | P4-06 |
| [P4-09](P4/P4-09-class-frame-editor.md) | 类 Frame 编辑器：多语言注解、父类、三种属性值、只读的其它公理、冲突处理 | wp-web | web | M | 完成 | P4-22、P4-26 |
| [P4-10](P4/P4-10-property-frame-editors.md) | 对象 / 数据 / 注解属性 Frame 编辑器 | wp-web | web | M | 完成 | P4-22、P4-26 |
| [P4-11](P4/P4-11-entity-create-delete.md) | 新建 / 删除实体：工具栏与树节点右键菜单，一次输入多个名称，删除前确认 | wp-web | web | M | 完成 | P4-07、P4-08 |
| [P4-12](P4/P4-12-entity-search.md) | 顶部搜索框：结果按实体类型分组，回车跳转并在树中定位 | wp-web | web | M | 完成 | P4-06 |
| [P4-13](P4/P4-13-revision-history.md) | 修订历史：项目历史列表、修订差异、实体历史、回滚（二次确认） | wp-web | web | M | 完成 | P4-05、P4-18 |
| [P4-14](P4/P4-14-project-events.md) | 实时事件（一）：SSE 解析、事件解析、按事件让 Vue Query 缓存失效 | wp-web | web | M | 完成 | P4-02、P4-18 |
| [P4-15](P4/P4-15-project-settings.md) | 项目设置页：标签页注册表与未保存提醒、基本信息、IRI 前缀 | wp-web | web | M | 完成 | P4-05、P4-18 |
| [P4-16](P4/P4-16-single-jar-packaging.md) | 单包打包：wp-app 的 with-web profile 把 wp-web 的 dist 打进可执行 jar，SPA 路由回退 | wp-app | build | S | 完成 | P4-01 |
| [P4-17](P4/P4-17-web-acceptance.md) | Web 端端到端验收（里程碑 M4）：Playwright 用界面走完 P3-24 的流程，两个浏览器上下文验证实时更新 | wp-web | e2e | M | 完成 | P4-04、P4-08、P4-09、P4-10、P4-11、P4-12、P4-13、P4-15、P4-16、P4-19、P4-20、P4-21、P4-23、P4-24、P4-27、P4-28、P4-29 |
| [P4-18](P4/P4-18-openapi-response-schemas.md) | 补齐 OpenAPI 中无类型的成功响应（层级、Frame、搜索、修订、个体、设置、事件）并重新生成 @wp/api-types | wp-app, packages/api-types | api-contract | M | 完成 | P3-23、P3-27 |
| [P4-19](P4/P4-19-download-menu.md) | 下载导出菜单：选择格式与修订号，下载项目本体 | wp-web | web | S | 完成 | P4-05 |
| [P4-20](P4/P4-20-account-settings.md) | 个人设置页：API Key 的创建与撤销、已登录设备的查看与撤销 | wp-web | web | M | 完成 | P4-03 |
| [P4-21](P4/P4-21-admin-user-management.md) | 用户管理页（系统管理员）：用户列表、创建用户 | wp-web | web | M | 完成 | P4-03 |
| [P4-22](P4/P4-22-frame-editor-foundation.md) | Frame 编辑机制：useFrameEditor（from / to 与 409 冲突）、保存栏、校验约定、标题与其它公理分区 | wp-web | web | M | 完成 | P4-05、P4-18 |
| [P4-23](P4/P4-23-individual-frame-editor.md) | 个体 Frame 编辑器：类型、对象属性断言、数据属性断言、同一个体 | wp-web | web | M | 完成 | P4-22、P4-26 |
| [P4-24](P4/P4-24-project-members.md) | 项目成员管理：列出成员、添加成员、修改角色、移除成员 | wp-web | web | M | 完成 | P4-15 |
| [P4-25](P4/P4-25-auth-login.md) | 登录页、当前用户、路由守卫与 401 统一跳转 | wp-web | web | M | 完成 | P4-02 |
| [P4-26](P4/P4-26-frame-inputs.md) | Frame 输入组件：实体选择器（含内置实体）、实体引用列表、字面量输入、注解编辑器 | wp-web | web | M | 完成 | P4-22 |
| [P4-27](P4/P4-27-entity-naming-settings.md) | 项目设置：新实体的 IRI 生成规则与显示语言（显示名称设置） | wp-web | web | M | 完成 | P4-15、P4-26 |
| [P4-28](P4/P4-28-admin-user-roles-password.md) | 用户管理（系统管理员）：修改应用角色、重置密码 | wp-web | web | S | 完成 | P4-21 |
| [P4-29](P4/P4-29-project-events-connection.md) | 实时事件（二）：订阅 SSE、断线重连带 Last-Event-ID、连接状态、接入项目外壳 | wp-web | web | M | 完成 | P4-05、P4-14 |

## P5

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P5-00](P5/P5-00-mobile-planning.md) | 规划卡：细化移动端（uni-app）MVP 任务卡 | tasks/P5 | planning | S | 阻塞 | P3-24、P3-27 |

## P6

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P6-00](P6/P6-00-extensions-planning.md) | 规划卡：按优先级细化扩展功能 | tasks/P6 | planning | S | 完成 | P3-24 |
| [P6-01](P6/P6-01-my-project-permissions.md) | 后端：查询“我在本项目中的角色与可用操作” | wp-app, packages/api-types | app-security | S | 完成 | P4-17 |
| [P6-02](P6/P6-02-member-candidate-search.md) | 后端：按用户 ID 或显示名称查找用户，供成员管理添加成员 | wp-app, packages/api-types | app-security | S | 完成 | P6-01 |
| [P6-03](P6/P6-03-event-subscription-revocation.md) | 后端：成员失去查看权限或项目移入回收站时，关闭对应的事件订阅 | wp-app | app-service | M | 完成 | P4-17 |
| [P6-04](P6/P6-04-web-permission-aware-actions.md) | Web 端：读取“我的权限”，按权限显示新建、删除与回滚操作 | wp-web | web | M | 完成 | P6-01 |
| [P6-05](P6/P6-05-web-read-only-editors.md) | Web 端：没有编辑权限时，Frame 编辑器为只读 | wp-web | web | M | 完成 | P6-04 |
| [P6-06](P6/P6-06-web-read-only-settings.md) | Web 端：没有对应权限时，项目设置表单为只读 | wp-web | web | S | 完成 | P6-04 |
| [P6-07](P6/P6-07-web-member-search.md) | Web 端：成员页按用户 ID 或显示名称查找并添加成员 | wp-web | web | S | 完成 | P6-02、P6-04 |
| [P6-08](P6/P6-08-outbox-relay.md) | outbox 中继：按消费者记录进度，按提交顺序投递修订事件 | wp-app | db | M | 完成 | P4-17 |
| [P6-09](P6/P6-09-projection-schema-writer.md) | 本体投影表 PoC（一）：projection 模块、表结构与写入器 | wp-app | db | M | 完成 | P6-08、P6-03 |
| [P6-10](P6/P6-10-projection-rebuild.md) | 本体投影表 PoC（二）：从内存模型计算投影行，并全量重建一个项目 | wp-app | app-service | M | 完成 | P6-09 |
| [P6-11](P6/P6-11-projection-benchmark.md) | 本体投影表 PoC（三）：50 万公理规模的重建与查询基准，按通过标准决定是否继续 | wp-app | benchmark | S | 完成 | P6-10、P6-17、P6-18 |
| [P6-12](P6/P6-12-projection-incremental.md) | 投影表增量更新：消费 outbox，只重算受影响的实体 | wp-app | db | M | 完成 | P6-11、P6-08 |
| [P6-13](P6/P6-13-projection-admin-docs.md) | 投影表的状态与重建接口（管理员），以及外部 SQL 访问说明 | wp-app, packages/api-types | app-security | M | 完成 | P6-12、P6-02 |
| [P6-14](P6/P6-14-mcp-poc.md) | MCP PoC：选定 MCP Java SDK，在 /mcp 提供 API Key 认证的只读工具 search_entities | wp-app | app-security | M | 完成 | P4-17 |
| [P6-15](P6/P6-15-mcp-browse-tools.md) | MCP 只读工具（一）：项目列表、层级浏览、实体 Frame，以及结果大小限制 | wp-app | app-service | M | 完成 | P6-14 |
| [P6-16](P6/P6-16-mcp-history-tools.md) | MCP 只读工具（二）：个体列表、修订历史、修订差异、实体历史，以及 MCP 使用说明 | wp-app | app-service | M | 完成 | P6-15 |
| [P6-17](P6/P6-17-projection-analyze-after-rebuild.md) | 投影表全量重建后刷新统计信息：提交后对 5 张投影表执行 ANALYZE | wp-app | db | S | 完成 | P6-10 |
| [P6-18](P6/P6-18-projection-benchmark-method.md) | 投影表基准测法修订：刷新统计信息与预热后采样，并记录执行计划、行数与后台活动 | wp-app | benchmark | S | 完成 | P6-17 |
| [P6-19](P6/P6-19-extensions-round2-planning.md) | 规划卡（第二轮）：结合 P6 已完成的卡，细化候选清单中尚未规划的扩展功能 | tasks/P6 | planning | S | 完成 | P6-05、P6-06、P6-07、P6-13、P6-16 |
| [P6-20](P6/P6-20-webhook-registry.md) | 后端：项目 webhook 的存储、回调地址校验与仓储（新增 integration 模块，BS-15） | wp-app | app-security | M | 完成 | P6-13 |
| [P6-21](P6/P6-21-webhook-delivery.md) | webhook 投递：outbox 消费者登记投递任务，独立的投递线程按 webhook 顺序发送，带签名、超时与指数退避重试 | wp-app | app-service | M | 完成 | P6-49 |
| [P6-22](P6/P6-22-web-webhook-settings.md) | Web 端：项目设置的 webhook 标签页（列表、新建与编辑、密钥只写、最近投递记录） | wp-web | web | M | 完成 | P6-49 |
| [P6-23](P6/P6-23-merge-entities-generator.md) | 内核：合并实体生成器（BS-13），替换引用、标签降级为 skos:altLabel、弃用或删除源实体 | wp-kernel | kernel | M | 完成 | P1-16 |
| [P6-24](P6/P6-24-merge-entities-api.md) | 后端：合并实体的命令与接口（新增动作 MERGE_ENTITIES，归入 CAN_EDIT） | wp-app | app-service | M | 完成 | P6-23、P6-49 |
| [P6-25](P6/P6-25-web-merge-entities.md) | Web 端：合并实体对话框（层级工具栏入口，选择目标与处理方式，按权限显示） | wp-web | web | M | 完成 | P6-24 |
| [P6-26](P6/P6-26-upload-merge-diff.md) | 内核：上传本体与项目的合并计划（新版本差异 / 只追加两种方式，BS-14）及合并生成器 | wp-kernel | kernel | M | 完成 | P1-21 |
| [P6-27](P6/P6-27-upload-merge-api.md) | 后端：上传合并的准备——新增两个动作、带归属与过期时间的上传暂存、列出项目本体的接口 | wp-app | app-service | M | 完成 | P6-24 |
| [P6-28](P6/P6-28-web-upload-merge.md) | Web 端：上传并合并到已有项目（工作台工具栏入口，选择方式、预览差异、确认应用） | wp-web | web | M | 待办 | P6-50、P6-22 |
| [P6-29](P6/P6-29-admin-delete-user.md) | 后端：系统管理员删除用户（停用并清除个人信息，账户 ID 保留在历史中） | wp-app | app-security | M | 待办 | P6-50 |
| [P6-30](P6/P6-30-web-delete-user.md) | Web 端：用户管理页删除用户，并标出已删除的用户 | wp-web | web | S | 待办 | P6-29 |
| [P6-31](P6/P6-31-instance-store-schema.md) | 实例库 PoC（一）：新建 wp-instances 模块，instances / links 表结构、带有效期的写入与追溯查询 | wp-instances | db | M | 完成 | P6-11 |
| [P6-32](P6/P6-32-instance-store-benchmark.md) | 实例库 PoC（二）：按诺和诺德仓库规模造数，测量 1 ~ 3 跳与深层追溯查询，按通过标准决定是否继续 | wp-instances | benchmark | S | 完成 | P6-31、P6-53、P6-54 |
| [P6-33](P6/P6-33-instance-store-validation.md) | 实例库 PoC（三）：从本体投影表生成定义域 / 值域校验规则，写入实例时校验 | wp-instances | db | M | 完成 | P6-32、P6-13 |
| [P6-34](P6/P6-34-instance-service-planning.md) | 规划卡：依据实例库 PoC 结论，规划实例库服务化 | tasks/P6 | planning | S | 完成 | P6-33 |
| [P6-35](P6/P6-35-outbox-kafka-publisher.md) | outbox 发布器 → Kafka：把每个修订事件按项目分区、按提交顺序发布到 Kafka 主题（默认关闭） | wp-app | app-service | M | 待办 | P6-21、P6-51 |
| [P6-36](P6/P6-36-graph-publisher-evaluation.md) | 规划卡：评估 outbox → 图数据库 / RDF 三元组库发布器（按 ADR-013 第 5 节的条件，产出 ADR 草案或“暂不做”的结论） | tasks/P6 | planning | S | 完成 | P6-32 |
| [P6-37](P6/P6-37-external-identities.md) | 后端：外部身份绑定（user_identities 表，按 issuer + subject 解析本地账户的规则），供 OIDC 与企业微信登录使用 | wp-app | app-security | M | 待办 | P6-29 |
| [P6-38](P6/P6-38-web-oidc-login.md) | Web 端：登录页显示“企业账号登录”入口，并提示外部账号未绑定等错误 | wp-web | web | S | 待办 | P6-51、P6-28 |
| [P6-39](P6/P6-39-wecom-login.md) | 后端：企业微信扫码登录（复用 P6-37 的外部身份绑定与会话建立） | wp-app | app-security | M | 阻塞 | P6-51 |
| [P6-40](P6/P6-40-multi-instance-planning.md) | 规划卡：多实例部署（按项目路由），产出 ADR 草案与拆卡 | tasks/P10 | planning | S | 阻塞 | P6-52、P6-27、P6-21 |
| [P6-41](P6/P6-41-web-vue-3-6-upgrade.md) | Web 端：升级到 Vue 3.6 正式版（不启用 Vapor 模式），验证生态库兼容 | wp-web | web | S | 阻塞 | P6-42 |
| [P6-42](P6/P6-42-web-typescript-7-evaluation.md) | Web 端：评估 TypeScript 7（原生编译器）与 vue-tsc、typescript-eslint 的兼容性，能升则升，不能升则记录阻塞点 | wp-web | web | S | 待办 | P6-07、P6-65 |
| [P6-43](P6/P6-43-forms-rea-planning.md) | 规划卡：表单（Forms）逆向分析，确定首期范围并拆卡 | tasks/P6 | planning | S | 完成 | P4-22 |
| [P6-44](P6/P6-44-discussions-rea-planning.md) | 规划卡：讨论与评论（实体级讨论线程）逆向分析，确定首期范围并拆卡 | tasks/P6 | planning | S | 完成 | P6-03 |
| [P6-45](P6/P6-45-watches-rea-planning.md) | 规划卡：关注（Watches）与邮件通知逆向分析，确定首期范围并拆卡 | tasks/P6 | planning | S | 完成 | P6-21 |
| [P6-46](P6/P6-46-tags-rea-planning.md) | 规划卡：标签（项目标签、实体标签、按条件自动打标签）逆向分析，确定首期范围并拆卡 | tasks/P6 | planning | S | 完成 | P3-19 |
| [P6-47](P6/P6-47-manchester-rea-planning.md) | 规划卡：Manchester 语法编辑逆向分析，确定首期范围并拆卡 | tasks/P7 | planning | S | 阻塞 | P4-09 |
| [P6-48](P6/P6-48-mcp-write-tools-planning.md) | 规划卡：MCP 写工具与人工确认机制（设计、ADR 草案与拆卡） | tasks/P8 | planning | S | 阻塞 | P6-16、P6-24 |
| [P6-49](P6/P6-49-webhook-management-api.md) | 后端：项目 webhook 的管理接口（增删改查、停用时取消待投递记录、最近投递记录） | wp-app | app-security | M | 完成 | P6-20 |
| [P6-50](P6/P6-50-upload-merge-apply-api.md) | 后端：上传并合并到已有项目的预览、应用与放弃接口（在生成锁内校验预览时的修订号） | wp-app | app-service | M | 待办（可开工） | P6-27、P6-26 |
| [P6-51](P6/P6-51-oidc-login-flow.md) | 后端：通用 OpenID Connect 登录（授权码流程、与账号密码登录相同的会话、登录方式列表接口） | wp-app | app-security | M | 待办 | P6-37、P6-52 |
| [P6-52](P6/P6-52-deleted-user-session-revocation.md) | 后端：已删除用户的已登录会话立即失效（会话注册表 + 按请求检查） | wp-app | app-security | M | 待办 | P6-29 |
| [P6-53](P6/P6-53-trace-follow-batched.md) | TraceQueries.follow 改为每一跳一条 SQL，消除逐节点查询（N+1） | wp-instances | db | S | 完成 | P6-31 |
| [P6-54](P6/P6-54-instance-benchmark-method.md) | 实例库基准测法修订：改用连接池，记录连接开销基线与 W1 失败次数 | wp-instances | benchmark | S | 完成 | P6-31 |
| [P6-55](P6/P6-55-projection-contract-version.md) | 编辑器：投影 project_state 增加 contract_version（V9），作为实例库读取投影表的契约版本 | wp-app/projection | db | S | 待办 | P6-37 |
| [P6-56](P6/P6-56-validation-rules-cache.md) | 实例库内核：按 project_state.rev_no 轮询刷新的校验规则缓存，并核对 contract_version | wp-instances | db | M | 待办 | P6-33、P6-55 |
| [P6-57](P6/P6-57-instances-app-poc.md) | PoC：新建 wp-instances-app Spring Boot 服务骨架（双数据源、Flyway、健康检查） | wp-instances-app | app-service | M | 待办（可开工） | P6-31 |
| [P6-58](P6/P6-58-instances-api-keys.md) | 实例库服务：独立 API Key 认证（存于 wp_instances，不复用编辑器密钥） | wp-instances-app | app-security | M | 待办 | P6-57 |
| [P6-59](P6/P6-59-instances-write-api.md) | 实例库服务：REST 批量写入（调用方时间戳、规则校验、单租户） | wp-instances-app | app-service | M | 待办 | P6-56、P6-58 |
| [P6-60](P6/P6-60-instances-read-api.md) | 实例库服务：REST 读取与多跳追溯查询 | wp-instances-app | app-service | M | 待办 | P6-59 |
| [P6-61](P6/P6-61-instances-compose-ops.md) | 实例库服务：Compose（同实例第二库）与备份、监控、限流说明 | root | ci-devops | M | 待办 | P6-57 |
| [P6-62](P6/P6-62-instances-network-write-bench.md) | 实例库服务：经网络接口的端到端批量写入基准（只测量，不优化） | wp-instances-app | benchmark | S | 待办 | P6-64、P6-61 |
| [P6-63](P6/P6-63-projection-constraints-planning.md) | 规划卡：扩展投影表以支持必填、基数、枚举取值（ADR 草案与拆卡） | tasks/P9 | planning | S | 阻塞 | P6-33 |
| [P6-64](P6/P6-64-instances-idempotency.md) | 实例库服务：写入幂等键（Idempotency-Key）与 instances OpenAPI 初稿 | wp-instances-app | app-service | M | 待办 | P6-60 |
| [P6-65](P6/P6-65-web-workspace-spec-isolation.md) | Web 端：WorkspacePage 测试与真实注册表隔离，修复 main 上 wp-web verify 的失败 | wp-web | web | S | 待办（可开工） | — |
| [P6-66](P6/P6-66-form-schema.md) | 表单：project_form / project_form_selector 表结构（V10）、JDBC 仓储与 Modulith form 模块壳 | wp-app/form | db | M | 待办 | P6-43、P6-55 |
| [P6-67](P6/P6-67-form-descriptor-api.md) | 后端：EDIT_FORMS 权限与表单描述符、选择器的增删改查接口 | wp-app/form | app-security | M | 待办 | P6-66、P6-51 |
| [P6-68](P6/P6-68-form-change-generator.md) | 内核：实体表单数据转公理变更生成器（BS-18 首期子集 TEXT/NUMBER） | wp-kernel | kernel | M | 待办（可开工） | P1-11、P6-26 |
| [P6-69](P6/P6-69-entity-forms-api.md) | 后端：按实体取表单接口（选择器子集与 PROPERTY 投影） | wp-app/ontology | app-service | M | 待办 | P6-67 |
| [P6-70](P6/P6-70-web-entity-forms.md) | Web 端：实体表单面板（TEXT/NUMBER 可编辑，其它控件只读占位） | wp-web | web | M | 待办 | P6-73 |
| [P6-71](P6/P6-71-forms-migrator-export.md) | 迁移工具：从 Mongo 导出 Forms 与 FormSelectors 到迁移包 | wp-legacy-migrator | migrator | M | 待办 | P6-66 |
| [P6-72](P6/P6-72-forms-migration-import.md) | 后端：导入迁移包中的表单描述符与选择器 | wp-app/migration | app-service | M | 待办 | P6-66、P6-71 |
| [P6-73](P6/P6-73-entity-forms-save-api.md) | 后端：保存实体表单接口（ChangePipeline、expectedHead 与 pristine 锚定） | wp-app/ontology | app-service | M | 待办 | P6-68、P6-69 |
| [P6-74](P6/P6-74-discussion-schema.md) | 讨论：entity_discussion_thread / comment 表结构（V11）、JDBC 仓储与 Modulith discussion 模块壳 | wp-app/discussion | db | M | 待办 | P6-44、P6-66 |
| [P6-75](P6/P6-75-discussion-permissions.md) | 访问控制：填实 CAN_COMMENT 的讨论动作（VIEW/CREATE/EDIT_OWN/SET_STATUS） | wp-app/access | app-security | S | 待办 | P6-44、P6-67 |
| [P6-76](P6/P6-76-discussion-api.md) | 后端：实体讨论 REST（线程与评论 CRUD、域事件） | wp-app/discussion | app-service | M | 待办 | P6-74、P6-75、P6-73、P6-82 |
| [P6-77](P6/P6-77-discussion-events.md) | 事件：讨论域事件接入 ProjectEvent / SSE，并扩展 api-types 联合类型 | wp-app/events | app-service | M | 待办 | P6-76、P6-03 |
| [P6-78](P6/P6-78-web-discussions.md) | Web 端：实体讨论面板与讨论事件的 applyProjectEvent 处理 | wp-web | web | M | 待办 | P6-77 |
| [P6-79](P6/P6-79-discussion-merge-transfer.md) | 后端：合并实体（DELETE）后将讨论线程转移到目标实体 | wp-app/discussion | app-service | S | 待办 | P6-74、P6-24 |
| [P6-80](P6/P6-80-discussions-migrator-export.md) | 迁移工具：从 Mongo 导出 EntityDiscussionThreads 到迁移包 | wp-legacy-migrator | migrator | M | 待办 | P6-74 |
| [P6-81](P6/P6-81-discussions-migration-import.md) | 后端：导入迁移包中的实体讨论线程 | wp-app/migration | app-service | M | 待办 | P6-74、P6-80、P6-82 |
| [P6-82](P6/P6-82-comment-renderer.md) | 讨论：服务端 CommentRenderer（CommonMark + 提及 span + HTML 消毒） | wp-app/discussion | app-service | S | 待办 | P6-74 |
| [P6-83](P6/P6-83-mail-delivery.md) | 邮件投递登记：mail_deliveries（V12）、MailEnqueue 与 MailProperties | wp-app/integration | db | S | 待办 | P6-45、P6-74 |
| [P6-84](P6/P6-84-watch-schema.md) | 关注：entity_watch 表结构（V13）、JDBC 仓储与 Modulith watch 模块壳 | wp-app/watch | db | M | 待办 | P6-45、P6-83 |
| [P6-85](P6/P6-85-watch-permissions.md) | 访问控制：CAN_VIEW 增加 WATCH_CHANGES | wp-app/access | app-security | S | 待办 | P6-45、P6-75、P6-77 |
| [P6-86](P6/P6-86-watch-api.md) | 关注 REST：按实体 get/set、列出当前用户关注（OpenAPI） | wp-app/watch | app-service | M | 待办 | P6-45、P6-84、P6-85、P6-77 |
| [P6-87](P6/P6-87-mail-dispatcher.md) | 邮件投递线程：spring-boot-starter-mail、SMTP 配置与认领/重试发送 | wp-app/integration | app-service | M | 待办 | P6-45、P6-83 |
| [P6-88](P6/P6-88-watch-outbox-notify.md) | 关注通知 outbox 消费者：按修订匹配关注并登记邮件投递 | wp-app/watch | app-service | M | 待办 | P6-45、P6-84、P6-83、P6-21 |
| [P6-89](P6/P6-89-web-watches.md) | Web：实体右键菜单关注对话框与「我的关注」页面 | wp-web | web | M | 待办 | P6-45、P6-86 |
| [P6-90](P6/P6-90-watches-migrator-export.md) | 迁移工具：导出 Mongo Watches 到交换包 | wp-legacy-migrator | migrator | S | 待办 | P6-45、P6-84、P6-81 |
| [P6-91](P6/P6-91-watches-migration-import.md) | 后端：导入交换包中的 Watches | wp-app/migration | app-service | S | 待办 | P6-45、P6-84、P6-90 |
| [P6-92](P6/P6-92-tag-schema.md) | 标签：project_tag / entity_tag 表结构（V14）、JDBC 仓储与 Modulith tag 模块壳 | wp-app/tag | db | M | 待办 | P6-46、P6-84 |
| [P6-93](P6/P6-93-tag-permissions.md) | 访问控制：CAN_MANAGE 增加 EDIT_PROJECT_TAGS，CAN_COMMENT 增加 EDIT_ENTITY_TAGS | wp-app/access | app-security | S | 待办 | P6-46、P6-85、P6-86 |
| [P6-94](P6/P6-94-tag-api.md) | 标签 REST：项目标签目录 get/set、实体显式标签 get/update（OpenAPI） | wp-app/tag | app-service | M | 待办 | P6-46、P6-92、P6-93 |
| [P6-95](P6/P6-95-entity-node-tags.md) | EntityNode 显式标签字段与层级/搜索响应填充 | wp-app/web | app-service | M | 待办 | P6-46、P6-94 |
| [P6-96](P6/P6-96-tag-events.md) | 标签高层事件：ProjectTagsChanged / EntityTagsChanged 进入 ProjectEventHub 与 api-types | wp-app/events | app-service | S | 待办 | P6-46、P6-77、P6-94、P6-95 |
| [P6-97](P6/P6-97-web-tags.md) | Web：项目标签设置、实体打标、chips、显示过滤与标签事件穷尽处理 | wp-web | web | M | 待办 | P6-46、P6-95、P6-96 |
| [P6-98](P6/P6-98-tags-migrator-export.md) | 迁移工具：导出 Mongo Tags 与 EntityTags 到交换包 | wp-legacy-migrator | migrator | M | 待办 | P6-46、P6-92、P6-91 |
| [P6-99](P6/P6-99-tags-migration-import.md) | 后端：从交换包导入 Tags 与 EntityTags | wp-app/migration | app-service | M | 待办 | P6-46、P6-92、P6-98 |

## P7

暂无卡片。候选清单见 [P7/EPIC.md](P7/EPIC.md)；拆卡的规划卡：[P6-47](P6/P6-47-manchester-rea-planning.md)（阻塞）。

## P8

暂无卡片。候选清单见 [P8/EPIC.md](P8/EPIC.md)；拆卡的规划卡：[P6-48](P6/P6-48-mcp-write-tools-planning.md)（阻塞）。

## P9

暂无卡片。候选清单见 [P9/EPIC.md](P9/EPIC.md)；拆卡的规划卡：[P6-63](P6/P6-63-projection-constraints-planning.md)（阻塞）。

## P10

暂无卡片。候选清单见 [P10/EPIC.md](P10/EPIC.md)；拆卡的规划卡：[P6-40](P6/P6-40-multi-instance-planning.md)（阻塞）。

各阶段的候选清单见 `P4/EPIC.md`、`P5/EPIC.md`、`P6/EPIC.md`、`P7/EPIC.md`、`P8/EPIC.md`、`P9/EPIC.md`、`P10/EPIC.md`。
