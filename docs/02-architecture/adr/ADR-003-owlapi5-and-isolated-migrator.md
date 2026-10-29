# ADR-003 内核用 OWL API 5；BinaryOWL 只在独立迁移工具中读取

- 状态：已接受
- 日期：2026-10-28

## 背景
旧系统的修订日志是 BinaryOWL 格式，`binaryowl 2.0.1` 绑定 OWL API 4.x。如果新工程继续用 BinaryOWL 存储，内核就被锁在 OWL API 4.5（方案一就是这样）。

## 决策
- `wp-kernel` / `wp-app` 使用 OWL API 5.5.x。
- 新增独立模块 `wp-legacy-migrator`：一个可执行 jar，使用 OWL API 4.5.13 + binaryowl 2.0.1，读取旧日志，输出与版本无关的迁移包（`interchange-format.md`）。
- `wp-legacy-migrator` 不得依赖 `wp-kernel` / `wp-app`，反之亦然。由 Enforcer 规则检查（P0-03）。
- 新工程的修订存储格式由 ADR-011 定义，与 BinaryOWL 无关。

## 理由
- 读旧数据是一次性需求，不应该决定内核十年的依赖版本。
- 迁移包用 OWL 函数式语法字符串承载公理，任何 OWL API 版本都能解析，可以人工检查。

## 后果
- 迁移需要两步：旧数据 → 迁移包 → 导入新系统（P3-10）。
- 迁移工具要单独维护一份很小的 OWL API 4 代码。
