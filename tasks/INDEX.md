# 任务索引

> 本文件由 `python tools/plan_lint.py` 生成并纳入 Git，请勿手工编辑；`tools/task.py finish` / `block` 会自动更新。状态以各卡片文件为准，主分支上的进度也可以运行 `python tools/task.py next` 查看。

共 99 张卡：完成 81，进行中 0，阻塞 0，待办 18。

## 现在可以开工的卡（按本地文件计算）

- [P4-12](P4/P4-12-entity-search.md) 顶部搜索框：结果按实体类型分组，回车跳转并在树中定位
- [P4-13](P4/P4-13-revision-history.md) 修订历史：项目历史列表、修订差异、实体历史、回滚（二次确认）
- [P4-15](P4/P4-15-project-settings.md) 项目设置页：标签页注册表与未保存提醒、基本信息、IRI 前缀
- [P4-19](P4/P4-19-download-menu.md) 下载导出菜单：选择格式与修订号，下载项目本体
- [P4-20](P4/P4-20-account-settings.md) 个人设置页：API Key 的创建与撤销、已登录设备的查看与撤销
- [P4-21](P4/P4-21-admin-user-management.md) 用户管理页（系统管理员）：用户列表、创建用户
- [P4-22](P4/P4-22-frame-editor-foundation.md) Frame 编辑机制：useFrameEditor（from / to 与 409 冲突）、保存栏、校验约定、标题与其它公理分区
- [P4-29](P4/P4-29-project-events-connection.md) 实时事件（二）：订阅 SSE、断线重连带 Last-Event-ID、连接状态、接入项目外壳
- [P5-00](P5/P5-00-mobile-planning.md) 规划卡：细化移动端（uni-app）MVP 任务卡
- [P6-00](P6/P6-00-extensions-planning.md) 规划卡：按优先级细化扩展功能

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
| [P4-09](P4/P4-09-class-frame-editor.md) | 类 Frame 编辑器：多语言注解、父类、三种属性值、只读的其它公理、冲突处理 | wp-web | web | M | 待办 | P4-22、P4-26 |
| [P4-10](P4/P4-10-property-frame-editors.md) | 对象 / 数据 / 注解属性 Frame 编辑器 | wp-web | web | M | 待办 | P4-22、P4-26 |
| [P4-11](P4/P4-11-entity-create-delete.md) | 新建 / 删除实体：工具栏与树节点右键菜单，一次输入多个名称，删除前确认 | wp-web | web | M | 完成 | P4-07、P4-08 |
| [P4-12](P4/P4-12-entity-search.md) | 顶部搜索框：结果按实体类型分组，回车跳转并在树中定位 | wp-web | web | M | 待办（可开工） | P4-06 |
| [P4-13](P4/P4-13-revision-history.md) | 修订历史：项目历史列表、修订差异、实体历史、回滚（二次确认） | wp-web | web | M | 待办（可开工） | P4-05、P4-18 |
| [P4-14](P4/P4-14-project-events.md) | 实时事件（一）：SSE 解析、事件解析、按事件让 Vue Query 缓存失效 | wp-web | web | M | 完成 | P4-02、P4-18 |
| [P4-15](P4/P4-15-project-settings.md) | 项目设置页：标签页注册表与未保存提醒、基本信息、IRI 前缀 | wp-web | web | M | 待办（可开工） | P4-05、P4-18 |
| [P4-16](P4/P4-16-single-jar-packaging.md) | 单包打包：wp-app 的 with-web profile 把 wp-web 的 dist 打进可执行 jar，SPA 路由回退 | wp-app | build | S | 完成 | P4-01 |
| [P4-17](P4/P4-17-web-acceptance.md) | Web 端端到端验收（里程碑 M4）：Playwright 用界面走完 P3-24 的流程，两个浏览器上下文验证实时更新 | wp-web | e2e | M | 待办 | P4-04、P4-08、P4-09、P4-10、P4-11、P4-12、P4-13、P4-15、P4-16、P4-19、P4-20、P4-21、P4-23、P4-24、P4-27、P4-28、P4-29 |
| [P4-18](P4/P4-18-openapi-response-schemas.md) | 补齐 OpenAPI 中无类型的成功响应（层级、Frame、搜索、修订、个体、设置、事件）并重新生成 @wp/api-types | wp-app, packages/api-types | api-contract | M | 完成 | P3-23、P3-27 |
| [P4-19](P4/P4-19-download-menu.md) | 下载导出菜单：选择格式与修订号，下载项目本体 | wp-web | web | S | 待办（可开工） | P4-05 |
| [P4-20](P4/P4-20-account-settings.md) | 个人设置页：API Key 的创建与撤销、已登录设备的查看与撤销 | wp-web | web | M | 待办（可开工） | P4-03 |
| [P4-21](P4/P4-21-admin-user-management.md) | 用户管理页（系统管理员）：用户列表、创建用户 | wp-web | web | M | 待办（可开工） | P4-03 |
| [P4-22](P4/P4-22-frame-editor-foundation.md) | Frame 编辑机制：useFrameEditor（from / to 与 409 冲突）、保存栏、校验约定、标题与其它公理分区 | wp-web | web | M | 待办（可开工） | P4-05、P4-18 |
| [P4-23](P4/P4-23-individual-frame-editor.md) | 个体 Frame 编辑器：类型、对象属性断言、数据属性断言、同一个体 | wp-web | web | M | 待办 | P4-22、P4-26 |
| [P4-24](P4/P4-24-project-members.md) | 项目成员管理：列出成员、添加成员、修改角色、移除成员 | wp-web | web | M | 待办 | P4-15 |
| [P4-25](P4/P4-25-auth-login.md) | 登录页、当前用户、路由守卫与 401 统一跳转 | wp-web | web | M | 完成 | P4-02 |
| [P4-26](P4/P4-26-frame-inputs.md) | Frame 输入组件：实体选择器（含内置实体）、实体引用列表、字面量输入、注解编辑器 | wp-web | web | M | 待办 | P4-22 |
| [P4-27](P4/P4-27-entity-naming-settings.md) | 项目设置：新实体的 IRI 生成规则与显示语言（显示名称设置） | wp-web | web | M | 待办 | P4-15、P4-26 |
| [P4-28](P4/P4-28-admin-user-roles-password.md) | 用户管理（系统管理员）：修改应用角色、重置密码 | wp-web | web | S | 待办 | P4-21 |
| [P4-29](P4/P4-29-project-events-connection.md) | 实时事件（二）：订阅 SSE、断线重连带 Last-Event-ID、连接状态、接入项目外壳 | wp-web | web | M | 待办（可开工） | P4-05、P4-14 |

## P5

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P5-00](P5/P5-00-mobile-planning.md) | 规划卡：细化移动端（uni-app）MVP 任务卡 | tasks/P5 | planning | S | 待办（可开工） | P3-24、P3-27 |

## P6

| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |
|---|---|---|---|---|---|---|
| [P6-00](P6/P6-00-extensions-planning.md) | 规划卡：按优先级细化扩展功能 | tasks/P6 | planning | S | 待办（可开工） | P3-24 |

各阶段的候选清单见 `P4/EPIC.md`、`P5/EPIC.md`、`P6/EPIC.md`。
