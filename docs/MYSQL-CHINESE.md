# MySQL 与中文默认更新

本页记录主包 `74CFBE76…` 的历史结果。后续 Redis 已由持久化后端改为 SQL 可选缓存，新产物、回归与实机故障测试见 [REDIS-CACHE.md](REDIS-CACHE.md)；下列 87 项测试及七服结果不计作新缓存版本的验证。

本次更新在首版预发行代码上增加 MySQL 8.x 后端，并将默认配置说明及展示示例中文化。原配置键、物品 ID、材料、权限节点、槽位编号和消息参数均保留程序格式。

## 配置与迁移

`storage.backend: MYSQL` 使用 `storage.mysql.url`、`username`、`password` / `password-env`、`pool-size`。MySQL Connector/J 8.4.0 随主包打包，兼容 Java 8。表使用 InnoDB、LONGBLOB 和区分字节的身份字段；等待行锁后才读取数据库时间，防止过期所有者继续保存。配置样例和持久化约束见 [STORAGE.md](STORAGE.md)。切换后端不会自动迁移现有 SQLite/PostgreSQL/Redis 数据。

默认语言为 `zh`；缺少语言字段、空白选择、不存在的语言文件和缺失消息项均使用中文默认内容。已有明确选择的其他语言与自定义消息继续保留。`config.yml`、`slots.yml`、`items.yml`、`backpacks.yml`、`pets.yml`、旧版宠物模板和插件描述的说明/示例已中文化；其他语言文件保留各自翻译，说明注释统一中文。

旧配置迁移新生成的欢迎文字和合成扩展示例改为中文，旧值识别条件及原有用户设置保持不变。`config-example.yml` 每次启动从内置中文主配置生成。已经存在的其他配置文件不会被整份覆盖；服主可参考中文示例合并设置，将已有 `language: en` 改为 `language: zh` 后重载生效。装备尚未加载时使用独立的中文加载提示，不再误报资源包被拒绝。

## 构建与回归

默认 API `26.1.2.build.74-stable` 和覆盖 API `26.2.build.121-stable` 各 **87 项测试通过，零失败、零错误、零跳过**。新增六项 MySQL 实际数据库测试覆盖工厂配置、超过 1 MiB 的二进制数据、连接池重开、精确身份、并发 CAS，以及通过 `performance_schema` 确认行锁等待后的获取/保存/续租到期行为。另有八项回归覆盖 YAML 解析、中文消息参数、默认/缺键回退和历史配置迁移。

本轮同时连接独立 MySQL 8.4.9、PostgreSQL 17.11、Redis 8.10.1，没有以跳过代替真实后端验证。构建证据位于 `build/verification/mysql-zh-default` 和 `mysql-zh-26.2`；两套 API 的主包、薄 API 包和 Probe 哈希一致。4079 个普通类均不高于 Java 8，JDBC 服务文件同时保留 MySQL、PostgreSQL 和 SQLite 驱动。POM/module 已加入 MySQL 运行依赖，未执行远端 Maven 发布。

| 产物 | SHA-256 |
| --- | --- |
| SX-RPGInventory-3.0.0-SNAPSHOT.jar | `74CFBE7638D338E29BF243C7202A85AE26C2F617EA24E315EE1DB7CF06F37B7C` |
| SX-RPGInventory-api-3.0.0-SNAPSHOT.jar | `D0926F517B2C21738949AB18F35C147E1563B1A94E2489DD611A23C3C1FF8530` |
| SX-RPGInventory-Probe-1.0.0.jar | `9F772C0DCADB3ABAB05E2E6DDE1527D0A607B8A797FF83DB4F55217C9415DEA0` |

## 七服 MySQL 与中文界面

<!-- MYSQL_MATRIX_START -->
| 服务端 | MySQL 生命周期与重启 | 实际界面标题 |
| --- | --- | --- |
| 1.12.2 | 通过 | 装备背包 |
| 1.16.5 | 通过 | 装备背包 |
| 1.20.6 | 通过 | 装备背包 |
| 1.21.11 | 通过 | 装备背包 |
| 26.1.2-spigot | 通过 | 装备背包 |
| 26.2-leaf | 通过 | 装备背包 |
| 26.2-paper | 通过 | 装备背包 |

完成 7/7。证据相对 `E:/Minecraft-Server/incisionTest/sxrpg-matrix-results`：

- `e2e-matrix-20260907-181021-8c59801f/matrix.json`
- `e2e-matrix-20260907-181306-e8b39a9d/matrix.json`
<!-- MYSQL_MATRIX_END -->

实测使用独立 MySQL namespace，覆盖实际装备、SX 属性增减、背包存取、放物后下一 tick kick、同 UUID 重连与完整服务端重启恢复。临时使用中文默认语言文件，并断言客户端实际收到的装备界面标题；现有配置和语言文件在测试后逐字节恢复。

Spigot 26.1.2 仍需临时隔离不支持该版本的 Adyeshach 2.1.30，测试不代表该 NPC 插件已兼容。首版三后端和 PacketEvents 测试的产物不同，历史证据见 [SERVER-MATRIX.md](SERVER-MATRIX.md)。本次没有重跑全部第三方插件组合、故障注入或生产负载测试。

<!-- MYSQL_CLEANUP_START -->
七服共 14 次 Minecraft 进程均正常退出，36 个配置、语言及隔离文件逐字节恢复；所有实际界面标题均为“装备背包”。生成的中文语言文件在 14 次启动中均与新包内置资源一致。两个独立汇总为本项目 `build/verification/mysql-zh-server-summary.json` 与测试目录 `sxrpg-matrix-results/mysql-chinese-coverage.json`，均确认 7/7 通过。

18 个临时探针、模板及新增客户端兼容依赖按原哈希归档到 `sxrpg-matrix-results/mysql-zh-cleanup-archive-20260907-181812-44a5f034`，清单 `cleanup-manifest.json` 的 `complete=true`；32 个保留插件 JAR 清理前后哈希一致，原有用户配置、数据、Spigot Adyeshach 和 Paper 26.2 的 Via 插件均保留。

独立 PostgreSQL/Redis 实例正常关闭；MySQL 通过其 Windows monitor 的正常停服事件完成刷盘与关闭，所属进程退出码为 0，原始日志含 `Normal shutdown` 和 `Shutdown complete`。关闭记录在 `sxrpg-db-tools/mysql-run-20260907-175407-2c4e8639/stopped.json`，三个测试端口不再监听。没有安装系统服务，也没有强制结束数据库进程。临时数据和完整证据保留用于追溯。
<!-- MYSQL_CLEANUP_END -->
