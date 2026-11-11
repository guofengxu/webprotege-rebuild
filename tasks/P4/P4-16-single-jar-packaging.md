---
id: P4-16
title: 单包打包：wp-app 的 with-web profile 把 wp-web 的 dist 打进可执行 jar，SPA 路由回退
phase: P4
module: wp-app
type: impl
size: S
work_type: build
ecc_extra: []
status: done
depends_on: [P4-01]
---

# P4-16 单包打包

## 目标
发布时先构建 wp-web，再用 wp-app 的 Maven profile `with-web` 打出一个可执行 jar：它在同一个端口上提供 Web 前端和 `/api`；前端路由（例如 `/projects/<id>/class`）直接打开或刷新时返回 `index.html`，`/api/**` 不回退。默认的 `./mvnw verify` 仍然不需要任何前端工具链。

## 参考
- `tasks/P4/EPIC.md` 第 16 项（验收要点照抄如下，不得弱化）
- ADR-008（生产构建产物由 wp-app 作为静态资源提供，单个可执行包部署）
- `docs/02-architecture/overview.md` 第 1 节：构建方向只有一条，Maven 不调用 Node
- P0-03（根 POM 已管理 `maven-enforcer-plugin` 3.7.0）
- `backend/wp-app/pom.xml`：当前**没有** `spring-boot-maven-plugin`，`package` 产出的是普通 jar，没有 `BOOT-INF/`
- `backend/wp-app/src/main/java/wprebuild/app/web/security/SecurityConfig.java`：安全过滤链只匹配 `/api/**`，静态资源不经过认证

## 产出
- `backend/wp-app/pom.xml`：
  - 默认 `<build>` 中加入 `org.springframework.boot:spring-boot-maven-plugin`（版本 `${spring-boot.version}`，与已导入的 Boot BOM 一致），执行 `repackage` 目标，使 `package` 产出可执行 jar。这是 Spring Boot 自带的插件，不是新的第三方依赖。
  - 新 profile `with-web`（默认不激活）：
    - `maven-enforcer-plugin` 的执行 `require-web-dist`，绑定在 `generate-resources`（早于 `process-resources`），规则 `requireFilesExist` 检查 `${maven.multiModuleProjectDirectory}/frontend/wp-web/dist/index.html`，失败消息为“缺少 frontend/wp-web/dist/index.html，请先运行 pnpm -C frontend --filter wp-web run build”；
    - `maven-resources-plugin` 的执行 `copy-web-dist`（目标 `copy-resources`，阶段 `process-resources`），把 `${maven.multiModuleProjectDirectory}/frontend/wp-web/dist` 复制到 `${project.build.outputDirectory}/static`。
- `backend/wp-app/src/main/java/wprebuild/app/web/SpaFallbackConfiguration.java`：`WebMvcConfigurer`，`addResourceHandlers` 注册 `/**` → `classpath:/static/`，自定义 `PathResourceResolver`：
  - 请求的资源存在 → 返回该资源；
  - 路径等于 `api` 或以 `api/` 开头 → 返回 `null`（不回退，得到 404 `ProblemDetail`）；
  - 路径的最后一段带扩展名（例如 `assets/missing.js`）→ 返回 `null`，得到 404；
  - 其它路径 → 返回 `static/index.html`（存在时），不存在时返回 `null`；
  - `index.html` 的响应带 `Cache-Control: no-cache`（带哈希的 `assets/*` 可以长缓存）；
  - 在 `application.yaml` 中设置 `spring.web.resources.add-mappings=false`，由本配置唯一负责静态资源，避免与 Boot 默认的 `/**` 处理器和欢迎页重复匹配。
- 修改 `backend/wp-app/src/main/java/wprebuild/app/web/security/SecurityConfig.java`：保留现有的 `/api/**` 过滤链不变，另加一条优先级更低、匹配其余全部路径的过滤链：`permitAll`、保留 Spring Security 默认的安全响应头（`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff` 等）、不创建会话、关闭 request cache 与 CSRF（这些路径只有静态 `GET`）。原来静态资源不经过任何过滤链，SPA 页面没有防点击劫持的响应头。
- 测试资源 `backend/wp-app/src/test/resources/static/index.html`（内容为一行标记文字，供测试断言）。
- 测试 `backend/wp-app/src/test/java/wprebuild/app/web/SpaFallbackTest.java`（`@DatabaseTest` + `@AutoConfigureMockMvc`，与 `OpenApiContractTest` 相同的写法）：`GET /projects/abc/class` 返回 200 且内容是测试用 `index.html`；`GET /index.html` 返回 200；`GET /assets/missing.js` 返回 404；未登录的 `GET /api/does-not-exist` 返回 401 的 `ProblemDetail`，不是 `index.html`；**已登录**（`@WithMockUser` 或会话登录）的 `GET /api/does-not-exist` 与 `GET /api` 返回 404 的 `ProblemDetail`，不是 `index.html`（未登录的 401 由安全过滤链返回，证明不了资源解析器不回退）；`GET /projects/abc/class` 的响应带 `X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff` 和 `Cache-Control: no-cache`。

## 实现要点
- 不引入 `frontend-maven-plugin`，Maven 不调用 Node；默认 `./mvnw verify` 不需要前端工具链（`with-web` 不激活时 enforcer 与复制都不执行）。
- `copy-resources` 遇到不存在的目录只会跳过、不会失败，所以必须先用 `requireFilesExist` 检查，并绑定在 `process-resources` 之前的阶段。
- 资源处理器的优先级低于控制器映射，已有的 `/api` 接口不受影响；回退只发生在没有其它处理器匹配的请求上。
- `${maven.multiModuleProjectDirectory}` 在 `-pl :wp-app` 时仍指向仓库根目录（wp-app 的 surefire 配置已在使用它）。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-16` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SpaFallbackTest
rm -rf frontend/wp-web/dist
./mvnw -q -pl :wp-app -am -DskipTests package
! ./mvnw -q -pl :wp-app -am -Pwith-web -DskipTests process-resources
pnpm -C frontend install
pnpm -C frontend --filter wp-web run build
./mvnw -q -pl :wp-app -am -Pwith-web package
test -f backend/wp-app/target/classes/static/index.html
python -c "import glob,sys,zipfile; jars=glob.glob('backend/wp-app/target/wp-app-*.jar'); sys.exit(0 if jars and all('BOOT-INF/classes/static/index.html' in zipfile.ZipFile(j).namelist() for j in jars) else 1)"
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 没有 `dist` 时 `-Pwith-web` 构建失败并提示先构建 wp-web
- [x] 默认 `./mvnw verify` 不需要 Node 或 pnpm
- [x] 执行记录已填写

## 不在本卡范围
- 修改 wp-web 的任何代码
- Docker 镜像、部署脚本（P6 候选）
- 在 CI 中构建带前端的 jar（`.github/` 属于开发标准文件，由维护者另行修改）

## 执行记录
- 日期：2026-11-11
- 分支：feature/p4-16-single-jar-packaging
- 测试：2026-11-11T17:30:12+08:00 通过（11 项）
- 实际改动：默认 package 产出可执行 jar；with-web 在缺少 dist 时失败并提示先构建 wp-web，成功时把 dist 打进 static。SPA 路由回退到 index.html，/api 仍返回 ProblemDetail，静态页带防点击劫持响应头。
- 偏差：Boot 4.1 在 add-mappings=false 时仍会为 / 注册欢迎页并转发到 index.html，不影响 /api 与带扩展名的资源。验收里的 process-resources -Pwith-web 在干净仓库上会先因 wp-kernel 未进入本地仓库而失败；去掉 dist 后执行 package -Pwith-web 才会走到 require-web-dist，失败信息与卡片要求一致。
- 审查：code-reviewer
