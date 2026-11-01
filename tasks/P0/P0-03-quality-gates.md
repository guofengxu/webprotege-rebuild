---
id: P0-03
title: 质量门禁：Spotless、Enforcer、JaCoCo
phase: P0
module: root
type: impl
size: S
work_type: build
ecc_extra: []
status: done
depends_on: [P0-02]
---

# P0-03 质量门禁：Spotless、Enforcer、JaCoCo

## 目标
`./mvnw verify` 自动检查格式、依赖收敛和禁用依赖，违反架构护栏的依赖会让构建失败。

## 参考
- ADR-002、ADR-003
- `.cursor/rules/10-architecture-guardrails.mdc`

## 产出
- 根 POM 配置 `spotless-maven-plugin`（palantir-java-format，`check` 绑定 verify 阶段），对现有代码执行一次 `spotless:apply`。
- 根 POM 配置 `maven-enforcer-plugin`：`requireJavaVersion [25,)`、`requireMavenVersion [3.9,)`。
- wp-kernel 与 wp-app 的 enforcer 规则：`dependencyConvergence`；`bannedDependencies` 禁止 `net.sourceforge.owlapi:*:[,5.0)`、`net.sourceforge.owlapi:binaryowl`、`org.mongodb:*`、`com.google.gwt:*`。
- wp-kernel 额外禁止 `org.springframework:*`、`org.springframework.boot:*`。
- wp-legacy-migrator 的 enforcer 规则：禁止 `wprebuild:wp-kernel`、`wprebuild:wp-app`、`org.springframework*:*`。
- 根 POM 配置 `jacoco-maven-plugin`：生成报告，本卡不设覆盖率阈值。

## 实现要点
- `dependencyConvergence` 报告的冲突如果来自第三方库之间（例如 OWL API 的传递依赖），在对应模块的 `dependencyManagement` 中显式指定版本来解决，并在执行记录中列出；**不得**关闭或放宽这条规则。
- 做一次反向验证：临时给 wp-kernel 加上 `net.sourceforge.owlapi:binaryowl:2.0.1`，确认构建失败，然后撤销。在执行记录里写明你做过这一步以及失败信息的第一行。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-03` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q verify
./mvnw -q spotless:check
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录中有反向验证的结果
- [x] 执行记录已填写

## 不在本卡范围
- 覆盖率阈值
- CI（P0-05）

## 执行记录
- 日期：2026-11-01
- 分支：feature/p0-03-quality-gates
- 测试：2026-11-01T19:53:56+08:00 通过（4 项）
- 实际改动：根 POM 配置 Spotless 3.10.4（palantir-java-format 2.103.0，check 绑定 verify）、Enforcer 3.7.0（requireJavaVersion [25,) 与 requireMavenVersion [3.9,)）和 JaCoCo 0.8.16（prepare-agent 与 verify 阶段 report，不设覆盖率阈值）。wp-kernel 与 wp-app 启用 dependencyConvergence，并禁止 net.sourceforge.owlapi:*:[,5.0)、binaryowl、org.mongodb:*、com.google.gwt:*；wp-kernel 另禁止 org.springframework:* 与 org.springframework.boot:*。wp-legacy-migrator 禁止 wprebuild:wp-kernel、wprebuild:wp-app 与 org.springframework*:*。已对现有代码执行 spotless:apply，仅 OwlApiSmokeTest 的一处换行被格式化。dependencyConvergence 的冲突来自 OWL API 5.5.1 的传递依赖，在对应模块的 dependencyManagement 中钉住 Maven 已经选中的版本，没有放宽规则，也没有改变解析结果。wp-kernel：commons-codec 1.15、commons-io 2.15.1、jackson-annotations/core/databind 2.16.1、jsonld-java 0.13.6、error_prone_annotations 2.21.1、guava 33.0.0-jre、httpclient 4.5.14、httpclient-cache 4.5.14、httpcore 4.4.16、checker-qual 3.37.0、jcl-over-slf4j 2.0.11。wp-app 上 Spring Boot BOM 已统一 jackson、commons-codec 1.21.0、jcl-over-slf4j 2.0.18、caffeine 3.2.4 与 httpcore 4.4.16，另钉住 commons-io 2.15.1、jsonld-java 0.13.6、error_prone_annotations 2.49.0、guava 33.0.0-jre、httpclient 4.5.14、httpclient-cache 4.5.14、checker-qual 3.41.0。反向验证：临时给 wp-kernel 加入 net.sourceforge.owlapi:binaryowl:2.0.1 后执行 ./mvnw -pl backend/wp-kernel validate，构建失败（退出码 1），随后已撤销该依赖。失败信息第一行：Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.7.0:enforce (enforce-architecture) on project wp-kernel:。下一行指出 Rule 1 BannedDependencies，以及 net.sourceforge.owlapi:binaryowl:jar:2.0.1 banned via the exclude/include list。
- 偏差：无
- 审查：code-reviewer
