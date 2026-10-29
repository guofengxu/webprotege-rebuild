---
id: P0-02
title: 依赖兼容性冒烟：OWL API 5 与 Spring Boot 4.1 共存
phase: P0
module: wp-kernel, wp-app
type: impl
size: S
work_type: build
ecc_extra: []
status: todo
depends_on: [P0-01]
---

# P0-02 依赖兼容性冒烟：OWL API 5 与 Spring Boot 4.1 共存

## 目标
证明 OWL API 5.5 能在 Java 25 上运行，并且能和 Spring Boot 4.1 放在同一个 classpath 上。

## 参考
- ADR-002、ADR-003

## 产出
- wp-kernel 依赖：`net.sourceforge.owlapi:owlapi-distribution:${owlapi5.version}`、`org.slf4j:slf4j-api`；测试依赖 `org.slf4j:slf4j-simple`。
- `wp-kernel/src/test/java/wprebuild/kernel/OwlApiSmokeTest.java`：用字符串构造一段 Turtle（含一个类、一个子类公理、一个中文 `rdfs:label`），用 `OWLManager.createConcurrentOWLOntologyManager()` 解析；再以函数式语法写出、重新解析；断言两次的公理集合相等。
- wp-app 依赖：`spring-boot-starter-web`、`spring-modulith-starter-core`、`wp-kernel`；测试依赖 `spring-boot-starter-test`、`spring-modulith-starter-test`。
- `wp-app/src/main/java/wprebuild/app/WpApplication.java`（`@SpringBootApplication`），`src/main/resources/application.yaml`（`spring.application.name: webprotege-rebuild`，`spring.threads.virtual.enabled: true`）。
- `wp-app/src/test/java/wprebuild/app/WpApplicationContextTest.java`：`@SpringBootTest` 能启动；同一个测试里调用 OWL API 解析一个最小本体，证明两套依赖共存。此时还没有数据库，如果自动配置需要数据源，就在测试里排除数据源自动配置，并在 P0-04 移除这个排除。

## 实现要点
- 运行 `./mvnw -pl wp-kernel,wp-app dependency:tree`，把 guava、caffeine、jackson-*、rdf4j-*、slf4j 最终解析到的版本写进执行记录。
- 如果 OWL API 的传递依赖和 Spring Boot BOM 管理的版本冲突导致运行失败，可以在 wp-app 中排除或显式指定版本，并把原因写进执行记录；若需要降级 Spring Boot 或 OWL API 的主版本，状态改为 `blocked`。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-02` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=OwlApiSmokeTest
./mvnw -q -pl wp-app -am test -Dtest=WpApplicationContextTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录中列出了关键传递依赖的最终版本
- [ ] 执行记录已填写

## 不在本卡范围
- 数据库（P0-04）
- Modulith 模块声明（P0-07）
