---
id: P0-01
title: 建立 Maven 多模块骨架与 Maven Wrapper
phase: P0
module: root
type: impl
size: S
work_type: build
ecc_extra: []
status: done
depends_on: []
---

# P0-01 建立 Maven 多模块骨架与 Maven Wrapper

## 目标
仓库可以用 `./mvnw verify` 构建三个空模块：wp-kernel、wp-app、wp-legacy-migrator。

## 参考
- ADR-001、ADR-002、ADR-003
- `docs/02-architecture/overview.md` 第 1 节

## 产出
- 根 `pom.xml`：`groupId=wprebuild`，`artifactId=webprotege-rebuild`，`version=0.1.0-SNAPSHOT`，`packaging=pom`；`<modules>` 列出三个子模块；`<properties>` 声明 `maven.compiler.release=25`、`project.build.sourceEncoding=UTF-8`，以及后续卡片会用到的版本属性：`spring-boot.version`（**固定为 4.1.1**，ADR-002）、`spring-modulith.version`（2.1 系列最新补丁）、`owlapi5.version`（5.5 系列最新）、`lucene.version`（10 系列最新）、`jqwik.version`、`assertj.version`、`junit-jupiter.version`。
- 根 `pom.xml` 的 `<pluginManagement>`：maven-compiler-plugin（`release` 25、`-parameters`）、maven-surefire-plugin（JUnit Platform）、maven-jar-plugin。
- `wp-kernel/pom.xml`、`wp-legacy-migrator/pom.xml`：`packaging=jar`，只依赖 JUnit Jupiter 与 AssertJ（test 范围）。
- `wp-app/pom.xml`：`packaging=jar`；在 `<dependencyManagement>` 中 import `org.springframework.boot:spring-boot-dependencies:${spring-boot.version}` 与 `org.springframework.modulith:spring-modulith-bom:${spring-modulith.version}`。**Spring BOM 只在 wp-app 中导入**，不放进根 POM（防止它管理 wp-legacy-migrator 中 OWL API 4 的传递依赖版本）。
- 每个模块一个占位类和一个测试：`wprebuild.kernel.KernelInfo`、`wprebuild.app.AppInfo`、`wprebuild.migrator.MigratorInfo`，各含 `public static final String NAME`；测试断言常量非空。
- Maven Wrapper（`mvn -N wrapper:wrapper -Dmaven=3.9.x`，取 3.9 系列最新），提交 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`。
- 在规划阶段已有的 `.gitignore` 末尾追加：target/、.idea/、*.iml、node_modules/、.DS_Store（不要删除已有条目）；`.gitattributes`（`* text=auto eol=lf`，`*.cmd eol=crlf`）、`.editorconfig`（UTF-8、LF、Java 缩进 4 空格）。

## 实现要点
- 版本号只能写在根 POM 的 properties 或 wp-app 的 dependencyManagement 里，子模块的依赖声明不写 `<version>`。
- 在执行记录中写明你实际选用的每个版本号。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-01` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -v
./mvnw -q verify
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] `./mvnw -v` 显示 Java 25
- [x] 三个模块的占位测试都被执行（查看 surefire 报告）
- [x] 执行记录已填写

## 不在本卡范围
- 任何 Spring、OWL API 依赖（P0-02）
- 代码格式化、Enforcer（P0-03）
- CI（P0-05）

## 执行记录
- 日期：2026-10-30
- 分支：feature/p0-01-repo-skeleton
- 测试：2026-10-30T21:15:06+08:00 通过（4 项）
- 实际改动：新增 wp-kernel / wp-app / wp-legacy-migrator 空模块、Maven Wrapper 3.9.16 与 Java 25 编译基线。版本：spring-boot 4.1.1、spring-modulith 2.1.2、owlapi5 5.5.1、lucene 10.5.2、jqwik 1.10.2、assertj 3.27.7、junit-jupiter 6.0.3、compiler-plugin 3.16.0、surefire 3.6.1、jar-plugin 3.5.1。
- 偏差：junit-jupiter 取 Spring Boot 4.1.1 BOM 的 6.0.3，而不是 Maven Central 最新的 6.1.3，避免 wp-app 导入 Boot BOM 后测试 classpath 混用两个 JUnit 小版本。
- 审查：code-reviewer
