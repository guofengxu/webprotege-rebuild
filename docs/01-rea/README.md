# 逆向分析（REA）结论

对象：`../webprotege`（上游 protegeproject/webprotege master，5.0.0-SNAPSHOT）。
方法：分五层逆向——边界与上下文 → 静态结构 → 动态行为 → 数据与状态 → 演进证据。每条结论都附代码出处。

本目录下的文件：

| 文件 | 内容 | 被谁引用 |
|---|---|---|
| `README.md` | 总结论、本质约束与偶然实现的划分 | ADR、规划 |
| `write-path.md` | 写路径时序、锁、不变量 | P1-10、P1-11、P3-02 |
| `behavior-specs.md` | 编号的行为规格 BS-xx，可直接写成测试 | 大部分 P1/P3 卡 |
| `legacy-data.md` | 旧数据目录、BinaryOWL 元数据、Mongo 集合 | P2 全部卡、P3-10 |

## 1. 边界与上下文

| 模块 | Java 行数（main） | 职责 |
|---|---|---|
| webprotege-client | 92,053 | GWT 2.8.2 前端（MVP 模式） |
| webprotege-server-core | 74,521 | 业务逻辑、Action Handler、索引、Mongo 仓储 |
| webprotege-shared | 49,630 | 前后端共享的 Action/Result DTO |
| webprotege-server-api | 4,910 | 服务端接口（含 `OntologyChange` 变更模型） |
| webprotege-server | 3,649 | WAR、Dagger 根装配、Jersey REST |
| webprotege-server-lucene | 2,819 | 短名字典与实体搜索 |
| webprotege-shared-core | 2,415 | 基础类型 |
| webprotege-cli | 595 | 管理命令 |

对外入口：GWT RPC `/webprotege/dispatchservice`（主通道）、Jersey `/data/projects/...`（API Key 认证，覆盖 axioms / revisions / forms / settings）、项目变更 webhook。

## 2. 静态结构

- **命令总线**：`webprotege-shared` 中 124 个 Action；服务端 140 个 Handler，其中 15 个继承 `AbstractProjectChangeHandler`（写操作）。
- **内存索引**：`server/index/impl/` 61 个类，包括按类型、按主语、按引用实体等维度的公理索引。
- **依赖注入**：Dagger 2.20。`ServerComponent` 是全局单例，每个项目一个 `ProjectComponent`（子组件），由 `ProjectCache` 缓存。

## 3. 动态行为（详见 `write-path.md`）

- **写**：`ChangeManager.applyChanges` 先加生成锁，生成变更，再把临时 IRI 换成正式 IRI；然后加写锁，计算有效变更，依次更新索引、字典、修订和层级树；释放写锁后发出高层事件，再调用 webhook。
- **读**：只查内存索引，**从不**为公理查询 Mongo。
- **加载与驱逐**：首次访问时创建 `ProjectComponent`，重放整份修订日志重建索引；`ProjectCache.purgeDormantProjects` 定期清理一段时间未访问的项目。
- **协同**：客户端 `EventPollingManager` 用 GWT Timer 轮询 `GetProjectEventsAction`。

## 4. 数据与状态（详见 `legacy-data.md`）

- **本体内容的唯一真源**是每个项目的 BinaryOWL 追加日志 `data-store/project-data/<projectId>/change-data/change-data.binary`。第 1 个修订就是导入的全部公理（`ProjectImporter`）。
- **元数据**在 Mongo：ProjectDetails、ProjectAccess、RoleAssignments、Users、PrefixDeclarations、EntityCrudKitSettings、Forms、FormSelectors、Tags、Watches、EntityDiscussionThreads 等。
- **派生数据**：内存索引、Lucene 索引（`lucene-indexes/`）、层级树。全部可以从日志重建。

## 5. 演进证据

- 共 5,483 次提交，高峰在 2017 年（1,038 次）和 2018 年（1,353 次），2021 年起几乎停止。上游已转向微服务形态的 webprotege-next-gen。
- 2019 年以后 server-core 中改动最多的目录：`index`（989 次文件变更）、`form`（418）、`frame`（220）、`change`（204）、`inject`（196）。
  - 解读一：上游后期做过一次大重构，用细粒度索引替换 `OWLOntology`，主要为了控制内存。
  - 解读二：表单（forms）是后期业务扩张最快的子系统。新工程 MVP 暂不纳入，放到 P6。

## 6. 本质约束与偶然实现

| 类别 | 内容 | 新工程 |
|---|---|---|
| 本质约束 | 事件溯源：修订日志是真源，修订号是一等对象，历史、回滚、差异对比都从日志派生 | **继承**（ADR-004） |
| 本质约束 | 项目是一致性边界：同一项目内写操作串行，读写互斥 | **继承**（ADR-006） |
| 本质约束 | 服务端拥有命名权：客户端提交临时 IRI，服务端生成正式 IRI 后回传映射 | **继承**（BS-05） |
| 本质约束 | 只持久化有效变更（先最小化，再过滤无效变更） | **继承**（BS-01、BS-02） |
| 本质约束 | 派生数据可重建 | **继承** |
| 本质约束 | 命令总线 + 统一的请求校验（权限） | **继承**（ADR-007） |
| 偶然实现 | GWT 2.8 前端、GWT RPC | 替换为 Vue Web 端 + uni-app 移动端 + REST（ADR-008、ADR-014） |
| 偶然实现 | BinaryOWL，并因此锁死 OWL API 4.5 | 核心改用 OWL API 5.5；BinaryOWL 只在独立迁移工具中读取（ADR-003） |
| 偶然实现 | MongoDB + Morphia 1.3 | 替换为 PostgreSQL（ADR-004） |
| 偶然实现 | 修订**异步**落盘（见下方缺陷） | 改为同步提交后再更新内存（ADR-004） |
| 偶然实现 | 客户端轮询事件 | 替换为 SSE（ADR-007） |
| 偶然实现 | 61 个手写索引 | 先用 OWL API 5 自带索引，基准不达标再补（ADR-005） |
| 偶然实现 | Dagger、Tomcat WAR | 替换为 Spring Boot 可执行包 |

## 7. 逆向中发现的缺陷（新工程必须规避）

1. **已确认的修订可能丢失。** `RevisionStoreImpl.persistChanges` 只有第 1 个修订同步写盘；之后的修订都提交给单线程执行器异步写入，`IOException` 只记日志。内存和索引在写盘之前就已更新，进程崩溃会丢掉已经返回给用户的修订。
   出处：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/revision/RevisionStoreImpl.java`，约第 176–198 行。
2. **按实体查询历史时扫描全部修订**，并忽略分页参数（`ProjectChangesManager.getProjectChanges` 中的注释写明了这一点）。新工程在修订表上为主语建索引（P3-18）。
3. **冷启动必须全量重放**，没有快照。大项目首次打开很慢。新工程引入快照（P1-07、P1-09）。
