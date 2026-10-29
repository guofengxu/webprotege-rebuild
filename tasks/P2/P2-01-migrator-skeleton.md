---
id: P2-01
title: 迁移工具骨架：OWL API 4 + BinaryOWL + CLI
phase: P2
module: wp-legacy-migrator
type: impl
size: S
work_type: migrator
ecc_extra: []
status: todo
depends_on: [P0-03]
---

# P2-01 迁移工具骨架：OWL API 4 + BinaryOWL + CLI

## 目标
得到一个可执行 jar，能在独立的 classpath 上加载 OWL API 4 和 BinaryOWL。

## 参考
- ADR-003
- `docs/01-rea/legacy-data.md`
- 旧依赖声明：`../webprotege/webprotege-server-core/pom.xml`（binaryowl 的 exclusion 写法）

## 产出
- 依赖：`net.sourceforge.owlapi:owlapi-distribution:4.5.13`；`net.sourceforge.owlapi:binaryowl:2.0.1`（照旧工程的写法排除它自带的 `owlapi-distribution`）；`info.picocli:picocli`；`com.fasterxml.jackson.core:jackson-databind`；`org.slf4j:slf4j-simple`。版本号写在本模块 POM 的 properties 中（本模块不使用 Spring BOM）。
- `maven-shade-plugin` 产出 `wp-legacy-migrator/target/wp-legacy-migrator.jar`，`Main-Class` 为 `wprebuild.migrator.Main`。
- `wprebuild.migrator.Main`：picocli 根命令，带 `--version`；子命令 `inspect`、`export`、`verify` 先占位，执行时输出 `not implemented` 并返回退出码 2。
- 测试 `MainTest`：`--help` 退出码 0；`--version` 输出包含版本号。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P2-01` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-legacy-migrator test -Dtest=MainTest
./mvnw -q -pl wp-legacy-migrator package && java -jar wp-legacy-migrator/target/wp-legacy-migrator.jar --help
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 读取旧数据（P2-02）
