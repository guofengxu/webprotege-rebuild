# webprotege-rebuild

WebProtégé 的重写工程。新建空工程、从零编码，只继承旧系统的**本质约束**（由逆向分析得出）和**数据**（经迁移工具转换），不继承旧代码。

- 后端：Spring Boot 4.1.1 模块化单体 + 纯 Java 内核（OWL API 5）+ PostgreSQL 18
- Web 端：Vue 3.5 + Vite 8 + Element Plus
- 移动端：uni-app（Vue3 版）
- 开发方式：AI 编码 Agent（Cursor + ECC）按任务卡逐张实现，一张卡一个分支一个 PR；每次提交与推送前由人确认

## 先读什么

| 读者 | 顺序 |
|---|---|
| AI 编码 Agent | `AGENTS.md` → `python tools/task.py start <卡号>` 打印的内容 → 卡片全文 → 卡片列出的 ADR / 规格 |
| 人（评审、规划） | `docs/00-planning-principles.md` → `docs/02-architecture/overview.md` → `docs/03-plan/roadmap.md` → `docs/04-dev-workflow.md` |

## 目录

```
AGENTS.md                         Agent 执行协议（必读）
CONTRIBUTING.md                   同事参与开发：环境配置、日常流程、评审、修改开发标准
.github/                          PR 模板、CODEOWNERS、main 分支保护配置（由 repo_setup.py init 从 tools/repo-templates/github/ 生成）
.githooks/                        Git 钩子：提交信息与人工确认尾注校验、禁止直接推送 main
.cursor/rules/                    Cursor 规则：任务协议、Git 规范、架构护栏、Java / Vue / uni-app 约定
.cursor/skills/wp-ecc-routing/    哪类工作用 ECC 的哪些 skill、agent、命令
.cursor/skills/git-naming-conventions/   分支、提交、PR、评论规范
docs/
  00-planning-principles.md       上次失败的原因与本次拆解原则
  01-rea/                         对旧 WebProtégé 的逆向分析结论（行为规格）
  02-architecture/                目标架构总览 + ADR（001 ~ 014）+ 迁移交换格式
  03-plan/                        阶段路线、MVP 范围
  04-dev-workflow.md              开发流程：分支 → 拉取 → 开发 → 单测 → 更新状态 → 人工确认 → 提交推送 → PR
tasks/
  TEMPLATE.md                     任务卡模板
  P0/ … P6/                       各阶段任务卡与候选清单（EPIC.md）
tools/
  task.py                         开发流程脚本（start / test / finish / commit / reject / block / next / status / ecc）
  plan_lint.py                    任务卡校验；生成 tasks/INDEX.md 与依赖图（纳入 Git，由 finish / block 自动更新）
  ecc-routing.json                工作项类型 → ECC 映射（唯一真源）
  check_ecc.py                    检查 ECC 是否已安装齐全
  repo_setup.py                   建仓（init）、分支保护（protect）、开发者环境检查（dev）
```

## 旧工程参照

旧代码作为**只读参照**，放在同级目录 `../webprotege`（即 `D:\00.workspace\00.ProjectCode\webprotege`）。任务卡里写的 `../webprotege/...` 路径都相对本仓库根目录。
在 Cursor 里建议用多根工作区，同时打开两个目录，旧工程只读不改。

## 常用命令

```bash
python tools/task.py next                 # 主分支上现在可以开工的卡
python tools/task.py start P0-01          # 从 GitHub 主分支建分支并拉取到本地
python tools/task.py test P0-01           # 本地测试：验收命令 + 全量检查
python tools/task.py finish P0-01 -m "build: add maven multi-module skeleton" --summary "…" --reviews "code-reviewer"
                                          # Agent 到此停止：更新卡片状态、暂存改动、生成 .task-runs/P0-01.review.md
python tools/task.py commit P0-01         # 【人】在自己的终端核对确认单，输入卡号后提交并推送
python tools/task.py reject P0-01 --reason "…"   # 【人】不同意，原因交给 Agent
python tools/plan_lint.py                 # 校验任务卡，并重新生成 tasks/INDEX.md 与依赖图
python tools/check_ecc.py                 # 检查 ECC 安装情况与提交闸门（--fix-hooks 修复）
```

新同事先读 `CONTRIBUTING.md`：克隆仓库后运行 `python tools/repo_setup.py dev` 配置本机环境。维护者首次建仓见 `CONTRIBUTING.md` 第 6 节。
