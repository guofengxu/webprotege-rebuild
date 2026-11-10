# M3 后端验收

日期：2026-11-11。脚本是 `BackendAcceptanceTest`，只通过 HTTP 调用正在运行的应用（`RestClient`，随机端口，Testcontainers）。

## 场景一：编辑闭环

| 步骤 | 调用 | 结果 |
|---|---|---|
| 管理员登录 | `POST /api/auth/login` | 200，会话 |
| 创建用户 B | `POST /api/admin/users` | 201，无应用角色 |
| 上传 `warehouse-sample.ttl` | `POST /api/projects/upload` | 201，新项目 |
| 把 `rdfs:label` 的空语言、`zh`、`en` 写入显示名称 | `PUT /api/projects/{id}/settings/display-names` | 200 |
| 把 B 设为 `CAN_EDIT` | `PUT /api/projects/{id}/members/{userId}` | 200 |
| B 签发 API Key | `POST /api/me/api-keys` | 201，明文只出现这一次 |
| B 用 API Key 订阅事件，并创建子类「周转托盘」 | `GET /api/projects/{id}/events/poll`，`POST /api/projects/{id}/entities` | 长轮询返回 `ProjectChanged` 与 `EntityHierarchyChanged`，子节点挂在 `wh:Pallet` 下 |
| 给该类加上英文标签 `Turnover pallet` | `PUT /api/projects/{id}/frames/class` | 200 |
| 搜索 `pallet` | `GET /api/projects/{id}/search?q=pallet` | 命中新类 |
| 查看修订历史 | `GET /api/projects/{id}/revisions` | 含 `Created class 周转托盘` 与 `Edited class 周转托盘` |
| 回滚最近一次修订 | `POST /api/projects/{id}/revisions/{rev}/revert` | 新修订描述为 `Reverted revision N` |
| 下载 ttl | `GET /api/projects/{id}/download?format=ttl` | `text/turtle`；仍有「周转托盘」「托盘」「堆垛机」，没有 `Turnover pallet`；`pallet` 不再命中该类 |
| 移入回收站 | `POST /api/projects/{id}/trash` | `inTrash=true`；项目列表不含它，回收站列表含它 |

默认显示名称只索引无语言的 `rdfs:label`。语言标签为 `en` 的英文标签要先写进显示名称设置，搜索才会命中。

## 场景二：移动端令牌与长轮询

| 步骤 | 调用 | 结果 |
|---|---|---|
| 管理员创建用户 B 和空项目，并把 B 设为 `CAN_VIEW` | 管理接口与成员接口 | 201 / 200 |
| B 换取访问令牌 | `POST /api/auth/tokens` | 200，访问令牌与刷新令牌不同 |
| 用 `Authorization: Bearer` 查询类层级 | `GET /api/projects/{id}/hierarchies/class/roots` | 200，根包含 `owl:Thing` |
| B 发起长轮询，管理员创建一个类 | `GET /api/projects/{id}/events/poll`，`POST /api/projects/{id}/entities` | 长轮询返回该类的 `ProjectChanged` 与 `EntityHierarchyChanged` |
| 用刷新令牌换新令牌 | `POST /api/auth/tokens/refresh` | 200，新访问令牌可以调用 `/api/auth/me` |
| 再次提交旧刷新令牌 | 同上 | 401，`REFRESH_TOKEN_REUSED` |

## 真实迁移包的 IC-7 / IC-8

包是 P2-05 对真实旧数据导出、并且 `verify` 退出码为 0 的那一份（四个成功项目；`0a3eaa4c-be4d-443b-9a43-99d853612b3c` 因语言标签 `xsd:datetime` 未能导出，不在包内）。2026-11-11 用 `ProjectMigrator.importPackage` 导入。IC-7 或 IC-8 失败会抛出并回滚该项目；这次导入没有抛出。

| 项目 | 导入后的头修订 | IC-7 / IC-8 |
|---|---|---|
| `45d5985f-f280-4c9b-8cb5-a703023224e1` | 1 | 通过 |
| `58eb4e67-6f40-45fa-a40e-d1b6e94b762e` | 0（空项目） | 通过 |
| `c193cbb4-76e8-4a77-8505-f5f55070536f` | 1 | 通过 |
| `e9ea2dbd-0f86-4d59-bb48-2be4ec5bfc3d` | 1（约 10779 条变更） | 通过 |

合计 4 个项目、3 条修订、1 个新建用户、9 条警告。警告是显示名称里的 `LocalName` / `PrefixedName` 被跳过，以及无法识别的实体创建套件回落到 UUID。新建用户没有密码，需要管理员重置后才能登录。
