# MVP 功能范围

依据：ADR-012。“旧出处”指旧系统中对应功能所在的目录（`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/<目录>`）。

## 纳入 MVP

| 功能 | 后端卡 | Web 端（P4） | 移动端（P5） | 旧出处 |
|---|---|---|---|---|
| 登录、登出、当前用户 | P3-04 | 登录页 | 登录页（令牌，P3-26） | `auth/`、`user/` |
| API Key / 设备会话 | P3-05、P3-26 | 个人设置 | 我的设备 | `api/`（旧版 REST 认证） |
| 项目创建、列表、详情、回收站 | P3-08 | 项目列表 | 项目列表（只读） | `project/` |
| 上传导入（多种格式、zip） | P3-09 | 上传对话框 | — | `upload/`、`project/ProjectImporter.java` |
| 旧数据迁移 | P2-*、P3-10、P3-11 | — | — | — |
| 下载导出（任意修订） | P3-12 | 下载菜单 | — | `download/` |
| 类与属性层级浏览 | P3-13 | 层级树 | 逐级列表 | `hierarchy/` |
| 创建、删除实体 | P3-14 | 新建 / 删除对话框 | — | `crud/`、`dispatch/handlers/Create*` |
| 类 Frame 查看与编辑 | P3-15、P3-16 | 类编辑器 | 只读详情 + 标签 / 注解轻量编辑 | `frame/` |
| 属性、个体 Frame | P3-17、P3-18 | 属性 / 个体编辑器 | 只读详情 | `frame/` |
| 实体搜索 | P3-19 | 搜索框 | 搜索页 | `search/`、`webprotege-server-lucene` |
| 修订历史、按实体查历史、回滚 | P3-20 | 历史面板 | 历史列表（不含回滚） | `revision/`、`change/` |
| 实时事件 | P3-21 | SSE 自动刷新 | 长轮询 + “有更新”提示 | `events/` |
| 项目设置、共享（角色分配） | P3-22 | 设置页 | — | `projectsettings/`、`sharing/` |
| 用户管理（管理员） | P3-25 | 管理页 | — | `user/` |

## 暂不纳入（P6 候选）

实例库与本体投影表（ADR-013）、表单（`form/`）、讨论与评论（`issues/`）、关注（`watches/`）、标签（`tag/`）、webhook（`webhook/`）、可视化（`viz/`）、合并实体（`merge/`、`merge_add/`）、批量操作（`bulkop/`）、CSV 导入（`csv/`）、OBO 相关视图（`obo/`）、Manchester 语法编辑（`mansyntax/`）、视图布局与透视图（`perspective/`）、OIDC / 企业微信登录、移动端 iOS 与小程序、推送通知、本体发布到图数据库 / Kafka、MCP 工具。

## 明确不做

- 兼容旧的 GWT RPC 接口和旧 `/data/*` REST 接口。
- 在新系统中保留 MongoDB。
- 迁移旧密码（见 `docs/01-rea/legacy-data.md` 第 3 节）。
- 把运行期的大规模本体实例放进编辑器（ADR-013）。
