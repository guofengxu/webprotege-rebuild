# ADR-002 技术基线

- 状态：已接受
- 日期：2026-10-28
- 修订：2026-10-29 —— Spring Boot 固定为 4.1.1；前端改为 Vue；新增移动端；PostgreSQL 升级到 18（原因见 ADR-013）。版本号核实于修订当日。

## 后端

| 项 | 选择 | 说明 |
|---|---|---|
| JDK | Java 25（LTS） | Maven Toolchains 固定；可用虚拟线程 |
| 构建 | Maven 3.9+ 与 Maven Wrapper | |
| 应用框架 | **Spring Boot 4.1.1（固定版本）** | 2026-08-20 发布。升级补丁版本也必须由人决定，并单独开卡 |
| 模块化 | Spring Modulith 2.1.x | 与 Boot 4.1 配套；取 2.1 系列最新补丁（2026-08 时为 2.1.1） |
| OWL | OWL API 5.5.x（`net.sourceforge.owlapi:owlapi-distribution`） | 核实时最新为 5.5.1 |
| 数据库 | **PostgreSQL 18** + Flyway | 18 系列已发布一年以上；实例库可选的 Apache AGE 1.7+ 只支持 18（ADR-013） |
| JDBC | Spring `JdbcClient` | 不引入 JPA / Hibernate |
| 缓存 | Caffeine（随 Boot BOM） | |
| 搜索 | Apache Lucene 10.x | 嵌入式 |
| 测试 | JUnit Jupiter（随 Boot BOM）、AssertJ、Testcontainers、jqwik | 内核测试不启动 Spring |
| 观测 | Micrometer（随 Boot） | |
| 格式化 | Spotless + palantir-java-format | |

## 前端（Web 端，ADR-008）

| 项 | 选择 | 核实时版本 |
|---|---|---|
| 运行时 | Node.js 24 LTS | |
| 包管理 | pnpm（工作区） | 12.x |
| 框架 | Vue 3.5.x | 3.5.44（3.6 仍为 RC，正式发布后单独开卡升级） |
| 构建 | Vite 8.x + `@vitejs/plugin-vue` 6.x | 8.3.5 |
| 语言 | TypeScript 6.0.x + vue-tsc 3.x | TypeScript 7 暂不采用：vue-tsc 依赖 TypeScript 的 JS API，等官方确认兼容后再升级 |
| 组件库 | Element Plus 2.x | 2.14.7；层级树用虚拟滚动的 `el-tree-v2` |
| 状态 | Pinia + TanStack Vue Query | 服务端数据走 Vue Query，界面状态走 Pinia |
| 路由 | Vue Router | 5.4.0 |
| API 客户端 | openapi-typescript 7 + openapi-fetch | 类型来自 `packages/api-types` |
| 测试 | Vitest + Vue Test Utils；Playwright（端到端） | Vitest 5.0.5、Playwright 1.64.1 |

## 移动端（ADR-014）

| 项 | 选择 | 核实时版本 |
|---|---|---|
| 框架 | uni-app Vue3 版（`@dcloudio/uni-app@vue3`），CLI 工程 | 3.0.0-5030420261028001（2026-10-28），内部固定 Vue 3.4.21、Vite 5.2.8 |
| 语言 | TypeScript 5.9.x | uni-app 工具链较旧，不跟随 Web 端的 TS 6 |
| 组件库 | wot-design-uni | 1.14.0 |
| 状态 | Pinia（使用 uni-app 兼容的版本，由 P5-01 确定） | |
| 测试 | Vitest（纯 TS 逻辑）；Playwright（H5 端到端） | |

## 规则
- Java 依赖版本只在父 POM 的 `<properties>` / `<dependencyManagement>` 中声明，子模块不写版本。
- 前端依赖版本写在各自的 `package.json` 中，并提交 `pnpm-lock.yaml`；Web 端与移动端的 Vue / Vite 版本**允许不同**，不得为了统一版本而修改 uni-app 自带的依赖。
- 新增依赖必须有任务卡明确提出（见 `AGENTS.md`）。
- 引入 Java 依赖后由 Maven Enforcer 的 `dependencyConvergence` 检查冲突（P0-03）。

## 被否决的选项
- Spring Boot 3.5（上一版方案一）：3.x 的最后一个系列，新工程不应从这里起步。
- React（本 ADR 初版）：按 2026-10-29 的决定改为 Vue，团队与移动端（uni-app）技术栈统一为 Vue。
