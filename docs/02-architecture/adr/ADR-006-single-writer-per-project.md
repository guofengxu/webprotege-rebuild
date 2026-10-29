# ADR-006 每个项目单写者，两段锁

- 状态：已接受
- 日期：2026-10-28

## 决策
- 每个 `ProjectRuntime` 持有一个 `ProjectWriteGate`：
  - **生成锁**（`ReentrantLock`）：生成变更、铸造 IRI、计算有效变更、持久化，整个过程串行；
  - **读写锁**（`ReentrantReadWriteLock`）：读操作持读锁；只在把变更应用到内存的那一小段持写锁。
- 加锁顺序固定：先生成锁，再写锁；读路径只拿读锁。禁止反向。
- 请求线程使用虚拟线程（`spring.threads.virtual.enabled=true`）；锁内不做网络 I/O，数据库提交除外。

## 理由
与旧系统 `ChangeManager` 的两把锁语义一致（`docs/01-rea/write-path.md`），已被长期使用验证；只是把持久化挪到了应用内存之前。

## 后果
- 单个项目的写吞吐受限于串行生成，对协同编辑的规模（几十人）足够。
- 不支持跨项目事务，也不需要。
