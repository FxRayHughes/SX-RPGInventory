# Redis 可选缓存更新

Redis 现在仅保存可丢弃的物品快照副本。SQLite、MySQL 或 PostgreSQL 是玩家装备、插件背包、revision 与所有权租约的唯一权威存储。默认关闭缓存；已有 SQL 数据无须迁移，开启缓存后按需回填。

## 配置和行为

在现有 SQL 配置下增加：

```yaml
storage:
  backend: MYSQL # 也支持 SQLITE、POSTGRESQL；保留该数据库原有连接配置
  redis:
    enabled: true
    uri: redis://localhost:6379/0
    uri-env: '' # 使用环境变量时填写变量名，变量必须由服务端进程继承
    ttl-seconds: 300 # 副本有效期，范围 1～86400 秒
    timeout-millis: 200 # 连接与命令超时，范围 1～1000 毫秒
```

读取先取得 SQL 租约和当前版本，只有该版本的缓存命中才省去 SQL 大块物品数据查询。保存先提交 SQL，再写带 TTL 的 Redis 副本；旧版本的延迟写入不能覆盖新版本。缓存过期、被清理、内容损坏或 Redis 断连时读取原数据库；连接故障熔断 5 秒，日志最多每 60 秒提示一次，恢复后自动尝试回填。

缓存命中仍然需要访问 SQL；数据库不可用、所有权冲突或租约过期时，缓存不能代替数据库接受存取。Redis 不需要 AOF 或 RDB 来保证装备持久性，数据库仍须独立备份。

新键使用 `sx-rpginventory:cache:v1:` 前缀，包含数据库地址与账号的身份摘要、namespace、记录类型、UUID 和 revision。恢复数据库备份、回滚版本或改换 schema 前须停服并清理对应的新缓存前缀，或改用独立 namespace，避免历史版本号复用。完整约束见 [STORAGE.md](STORAGE.md)。

旧 `storage.backend: REDIS` 明确拒绝启动。必须先停服备份并将旧记录迁移、核验到 SQL；目前没有自动迁移命令，不能只修改 backend 指向空库。升级不会删除旧 Redis 持久化键，也不会将它们当作缓存读取。

## 构建与回归

默认 Paper API `26.1.2.build.74-stable` 与覆盖 API `26.2.build.121-stable` 各通过 **100 项测试，零失败、零错误、零跳过**，主包和薄 API 包哈希一致。证据分别保存在 `build/verification/redis-cache-default` 与 `build/verification/redis-cache-26.2`；最终发布描述已恢复默认 API。

本轮使用真实 MySQL 8.4.9、PostgreSQL 17.11 和 Redis 8.10.1。Redis 测试实例关闭 AOF/RDB，采用可淘汰缓存配置；原有持久化测试目录保留。

新增回归覆盖实际缓存命中、TTL 到期、删除、五类损坏封装、迟到旧版本写入、SQL 提交后才发布、数据库故障与租约冲突、Redis 拒连/断连/恢复、三个 SQL 后端启用缓存、账号隔离以及非法 URI 异常不泄露密码。缓存代理故障只作用于测试自己的连接，未停止其他回归共享的 Redis。

主包 4080 个普通类均不高于 Java 8；JDBC 服务声明包含 SQLite、PostgreSQL、MySQL 驱动。旧 `RedisInventoryRepository.class` 和 `storage/record.lua` 均不再存在于产物中。

| 产物 | SHA-256 |
| --- | --- |
| SX-RPGInventory-3.0.0-SNAPSHOT.jar | `2302DFBD814790158654F5B20A7F0E50D07C37DFB61FDB5C5B31A511CEF959DF` |
| SX-RPGInventory-api-3.0.0-SNAPSHOT.jar | `D7AFB374B4A2FD6F9D8CEA44EA627568F54FDC664DB67688BA6A9055FE4CE336` |
| SX-RPGInventory-Probe-1.0.0.jar | `9F772C0DCADB3ABAB05E2E6DDE1527D0A607B8A797FF83DB4F55217C9415DEA0` |

## 七服 MySQL 与 Redis 缓存实测

测试根目录为 `E:/Minecraft-Server/incisionTest`。以下结果均对应本页 `2302DFBD…` 主包，使用 MySQL 权威存储、Redis 可选缓存和中文配置。

| 服务端 | 生命周期与正常重启 | 客户端装备标题 |
| --- | --- | --- |
| Paper 1.12.2 build 1620 / Java 8 | 通过 | 装备背包 |
| Paper 1.16.5 build 794 / Java 16 | 通过 | 装备背包 |
| Paper 1.20.6 build 151 / Java 21 | 通过 | 装备背包 |
| Paper 1.21.11 build 132 / Java 21 | 通过 | 装备背包 |
| Spigot 26.1.2 / Java 25 | 通过，见隔离条件 | 装备背包 |
| Leaf 26.2 build 42 / Java 25 | 通过 | 装备背包 |
| Paper 26.2 build 84 / Java 25 | 通过 | 装备背包 |

七服覆盖实际装备穿脱、SX 属性增减、物品身份和数量、插件背包存取、放物后下一 tick 踢出、同 UUID 重连、完整进程重启及二进制编码存储回读。实际 Redis 键含对应数据库地址与账号的身份摘要、测试 namespace 与 revision，观察到正数且不超过 300 秒的 TTL。

证据相对 `sxrpg-matrix-results`：`e2e-matrix-20260907-184417-1401c1c1/matrix.json` 与 `e2e-matrix-20260907-184806-89427f76/matrix.json`。首轮四服因测试抗性命令早于装备加载完成，被命令保护取消而未进入物品测试；调整脚本时序后四服全部补跑通过。失败记录保留，不能视为首轮七服全过。测试仅给本轮独立玩家添加抗性，避免已有世界生物击杀干扰界面流程。

Spigot 26.1.2 的原有 Adyeshach 2.1.30 会阻止登录，本轮继续按原哈希临时隔离并恢复。结果不代表该 NPC 插件或原目录完整插件组合已兼容。旧服未安装协议覆盖库，现代五服保留 PacketEvents 2.13；本轮没有重复整套数据包定向断言，历史记录另列。

## 实机缓存中断与空缓存恢复

代表服 Paper 1.20.6 使用同一个 MySQL namespace、玩家账号与背包 UUID `43476aae-58d7-42cb-af34-01fa4efdfb5f` 完成以下检查，三个 Minecraft 进程均正常退出：

| 阶段 | 结果 |
| --- | --- |
| Redis 在线时完成装备和背包写入 | 通过，PID 41440 |
| 正常停止临时 Redis，保持 Minecraft 原 PID | 通过，PID 41440；物品回读及 codec-storage 保存/读取/释放成功 |
| Redis 仍离线，完整重启 Minecraft | 通过，PID 32304；同背包恢复且 SQL 编码存储成功 |
| 同地址启动全新空 Redis，再重启 Minecraft | 通过，PID 36652；初始 DBSIZE 为 0，同背包恢复后生成 5 个带 TTL 的缓存键 |

四次客户端操作均保留同一背包 UUID。后续 verify 只检查已有背包，不重新发放装备或重建测试物品；正常登录/退出继续通过 SQL 保存。新缓存实例关闭 AOF/RDB、使用全新目录，没有复用旧数据或删除旧持久化键。父侧独立查询确认回填键的数据库身份及 TTL 正确。

证据为 `sxrpg-matrix-results/e2e-matrix-20260907-185342-1ab5c25a/matrix.json`，同目录保留 `redis-before-outage.json`、`redis-after-empty-cache-recovery.json`、`redis-recovery-parent-verification.json`，以及 `1.20.6/cache-fault-gates` 下的停服/恢复请求与确认。普通七服汇总为 `redis-cache-ordinary-coverage.json`；独立全轮汇总在项目 `build/verification/redis-cache-server-summary.json`。

临时 PostgreSQL、MySQL 和 Redis 均正常关闭，未强制终止数据库进程。MySQL 关闭记录为 `sxrpg-db-tools/mysql-run-20260907-182544-fe5364ee/stopped.json`，新缓存关闭记录为 `sxrpg-db-tools/redis-cache-run-20260907-185728-0a5d5829/stopped.json`，两者退出码均为 0。

最终汇总 `sxrpg-matrix-results/redis-cache-final-coverage.json` 的 `complete=true`。18 项测试探针、模板及临时 Via 输入按精确哈希归档到 `redis-cache-cleanup-archive-20260907-185953-c1e4242e/cleanup-manifest.json`；60 项保留插件与原配置哈希一致，原有语言文件存在性和 Adyeshach 均恢复。七服保留最终主包，无测试 JVM 或矩阵锁遗留；三个临时数据库端口均不再监听。

## 证据范围

历史 MySQL/中文版本和首版的三后端、原生槽、PacketEvents 实测分别保留在 [MYSQL-CHINESE.md](MYSQL-CHINESE.md) 与 [SERVER-MATRIX.md](SERVER-MATRIX.md)。其中 Redis 持久化测试属于已移除的旧实现，不能计作本轮缓存验证。默认语言与配置注释继续为中文；本次没有远端 Maven 发布，也不以本地通过代替 GitHub Actions 的实际执行。
