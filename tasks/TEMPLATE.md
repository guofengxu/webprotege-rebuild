---
id: PX-NN
title: 一句话说明交付什么
phase: PX
module: wp-kernel | wp-app/<模块> | wp-legacy-migrator | wp-web | wp-mobile | packages/<包> | root
type: impl            # impl | planning
size: S               # S ≤ 200 行，M ≤ 400 行（不含测试与测试数据）
work_type: kernel     # 取值见 tools/ecc-routing.json 的 workTypes，决定用哪些 ECC skill 和全量检查
ecc_extra: []         # 可选：rea | concurrency | security | docker | milestone
status: todo          # todo | in_progress | blocked | done（由 tools/task.py 维护）
depends_on: []
---

# PX-NN 标题

## 目标
一句话：完成后系统多了什么能力。

## 参考
- 本卡依据的 ADR、规格编号（BS-xx / WP-x / IC-x）、旧工程文件路径。

## 产出
- 逐条列出要新建或修改的文件、类、关键方法签名。
- 没写到的类不要建。

## 实现要点
- 必须遵守的约束、容易踩的坑、可以借鉴的旧代码位置。

## ECC 技能
- 照抄 `python tools/task.py ecc PX-NN` 的输出。

## 验收
```bash
# 至少一条可以直接执行的命令；tools/task.py test 会逐条执行
./mvnw -q -pl <模块> test -Dtest=<测试类>
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 明确列出容易顺手做、但不属于本卡的事。
