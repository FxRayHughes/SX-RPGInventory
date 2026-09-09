# 实机兼容性矩阵

本页保留首个已发布预发行版的历史基线（主包 `E5550B72…`）。后续 MySQL 与中文默认更新使用不同产物，新增构建和实测见 [MYSQL-CHINESE.md](MYSQL-CHINESE.md)，不将下列旧产物的测试当作新包重新运行的结果。

下表 Redis 列属于现已移除的旧持久化后端，并非缓存测试。当前版本仅允许 SQL 作为权威存储；Redis 可选缓存的独立验证见 [REDIS-CACHE.md](REDIS-CACHE.md)。

验收日期：2026-09-07。测试根目录：`E:/Minecraft-Server/incisionTest`。下表仅代表所列具体服务端构建及测试操作；Spigot 的第三方插件隔离条件见下文。

## 产物与构建

| 产物 | SHA-256 |
| --- | --- |
| SX-RPGInventory-3.0.0-SNAPSHOT.jar | `E5550B724069400BCAF1909E163D7FD0E8C2DC4490B7BFBB618439DB0416D58A` |
| SX-RPGInventory-api-3.0.0-SNAPSHOT.jar | `1C729222C923788138D0FC6D343EE95B81970B895925879FB5FA653EF1FD2903` |
| SX-Attribute-4.0.0-beta.9-all.jar | `9560AE6D5AD9B532E73B2142447333780DD588F8C76420C74E7AD0D77963BE2F` |
| 最终诊断 Probe 1.0.0 | `9F772C0DCADB3ABAB05E2E6DDE1527D0A607B8A797FF83DB4F55217C9415DEA0` |

默认 API `26.1.2.build.74-stable` 与覆盖 API `26.2.build.121-stable` 各 73 项测试通过，无失败、无跳过；两套主包及薄 API 包相同，最终配置恢复默认。完整构建证据在项目 `build/verification/placeholders-default` 与 `placeholders-26.2`。

早期生命周期与原生槽测试使用 Probe `E52967CF380862DD2A36613AD0E412899BA9CCB531DC84A2D872A09CB48480BA`。后续只增加定向 SET_SLOT 和只读启动诊断，独立编译探针为上表版本，生产主包未变。探针不是生产依赖。

## 验收结果

<!-- MATRIX_RESULTS_START -->
| 服务端 | SQLite | PostgreSQL | Redis | 原生槽 | PacketEvents |
| --- | --- | --- | --- | --- | --- |
| 1.12.2 | 通过 E1 | 通过 E3 | 通过 E7 | 通过 E1 | 不适用（未装协议库） |
| 1.16.5 | 通过 E1 | 通过 E3 | 通过 E7 | 通过 E1 | 不适用（未装协议库） |
| 1.20.6 | 通过 E2 | 通过 E3 | 通过 E8 | 通过 E2 | 通过 E9 |
| 1.21.11 | 通过 E2 | 通过 E3 | 通过 E8 | 通过 E2 | 通过 E9 |
| 26.1.2-spigot | 通过 E4 | 通过 E6 | 通过 E8 | 通过 E4 | 通过 E9 |
| 26.2-leaf | 通过 E2 | 通过 E3 | 通过 E8 | 通过 E2 | 通过 E10 |
| 26.2-paper | 通过 E2 | 通过 E5 | 通过 E8 | 通过 E2 | 通过 E9 |

完成数：后端生命周期 21/21；原生槽 7/7；现代 PacketEvents 5/5。

证据索引（相对 `E:/Minecraft-Server/incisionTest/sxrpg-matrix-results`）：

- E1：`e2e-matrix-20260907-165624-bb4d9102/matrix.json`
- E2：`e2e-matrix-20260907-170053-0e14b8b8/matrix.json`
- E3：`e2e-matrix-20260907-170837-ab7d18bb/matrix.json`
- E4：`e2e-matrix-20260907-171605-e1c824ae/matrix.json`
- E5：`e2e-matrix-20260907-171615-bfd1e65f/matrix.json`
- E6：`e2e-matrix-20260907-172049-84df5468/matrix.json`
- E7：`e2e-matrix-20260907-172059-340a2847/matrix.json`
- E8：`e2e-matrix-20260907-172451-9b81a48d/matrix.json`
- E9：`e2e-matrix-20260907-173023-35e3ea74/matrix.json`
- E10：`e2e-matrix-20260907-173454-858605f0/matrix.json`
<!-- MATRIX_RESULTS_END -->

每个后端的“通过”都要求实际装备/卸下/再次装备、装备事件和 SX 生命 20→27→20、物品 ID/私有数据/数量、背包存取重开、放入后下一 tick 被踢出、同背包 UUID 重连、完整 Minecraft 进程重启后恢复，以及完整物品 codec-storage 的保存/释放/重新获取/读取。原生槽专项包含护甲、快捷栏和手持切换、副手 F 换手及属性去重。

现代 PacketEvents 专项独立执行，包含自然 WINDOW_ITEMS 覆盖、被拒绝点击后的游标/合成格/数量守恒、经正常发送链路定向验证 SET_SLOT、真实配方请求拦截，以及重载和停用后的监听器状态。它不计入后端生命周期通过数。旧两服没有安装 ProtocolLib，本轮只验收其装备/背包核心；没有宣称旧版数据包桥已实测。

## 服务端与依赖

| ID | 相对测试根目录的服务端 JAR | 实际构建 | Java 目录（D:/Java 下） |
| --- | --- | --- | --- |
| 1.12.2 | run-1.12.2/paper-1.12.2-1620.jar | git-Paper-1620 | zulu-8 |
| 1.16.5 | run-1.16.5-paper/paper-1.16.5-794.jar | git-Paper-794 | zulu16.32.15-ca-jdk16.0.2-win_x64 |
| 1.20.6 | run-1.20.6-paper/paper-1.20.6-151.jar | Paper 151 / a4f0f5c | azul-21.0.10 |
| 1.21.11 | run-1.21.11-paper/paper-1.21.11-132.jar | Paper 132 / c5eb079 | azul-21.0.10 |
| 26.1.2-spigot | run-26.1.2-spigot/spigot-26.1.2.jar | 4620-Spigot-566f972-3347052 | zulu25.32.21-ca-jdk25.0.2-win_x64 |
| 26.2-leaf | run-26.2-leaf/leaf-26.2-42.jar | Leaf 42 / b801131 | zulu25.32.21-ca-jdk25.0.2-win_x64 |
| 26.2-paper | run-26.2-paper/paper-26.2-84.jar | Paper 84 / 26e81c4 | zulu25.32.21-ca-jdk25.0.2-win_x64 |

七服均使用 SX-Item 4.5.10、Vault 1.7.3-b131 和上表 SX 产物；现代五服安装 PacketEvents 2.13.0。26.x 客户端测试经 ViaVersion/ViaBackwards 5.11.0 使用 1.21.11 协议。每轮原始 JSON 保留所有插件和脚本的完整哈希。

Spigot 26.1.2 原有 Adyeshach 2.1.30 明确报告不支持该 Minecraft 版本。只读诊断在 `sxrpg-matrix-results/20260907-171136-9ecbb42d/26.1.2-spigot.log` 确认四个未完成的 Adyeshach 本地启动任务，包括 `adyeshach_description_init`、`adyeshach_generate_entity_class`，使 TabooLib 共享启动守卫持续拒绝登录。每轮 Spigot 玩家测试只临时隔离该 JAR，按原路径和哈希备份并恢复；没有绕过守卫或强行完成任务。**Spigot 通过不包含这个不支持当前版本的 NPC 插件，原有全部插件组合不能视为通过。**

1.16 的既有 SkillAPI 启动失败，已在 SX-Attribute 修复可选来源保护，让核心生命刷新继续工作；不能将此写为 SkillAPI 本身兼容。ChestSort 只完成 API 事件协议测试，未安装真实插件实测。其他完整验收边界见 [MODERNIZATION.md](MODERNIZATION.md)。

## 复验与证据

本机编排入口 `devdump/sxrpg-e2e-matrix.py` 使用独立测试账号和 namespace、回环测试端口，并通过自己启动的服务端 stdin 正常 stop。每轮保存独立 `sxrpg-matrix-results/e2e-matrix-*/matrix.json`、客户端 JSON 和原始控制台日志。操作只发送一次，随后只读查询确认权威状态；异步加载期间的只读超时重试保持原 30 秒截止时间，不重复写操作。

```powershell
# 本机 JDK 16+ 使用此短目录建立 Gradle/服务端所需的本地套接字。
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=E:/Code/Work/Minecraft/SX-RPGInventory/.gradle/sockets'

# 将两个占位值替换为上表产物的真实哈希。
python E:/Minecraft-Server/incisionTest/devdump/sxrpg-e2e-matrix.py --servers all --backend SQLITE --workers 3 --sx-item SXRPGProbeRing --attribute-delta 7 --kick-after-deposit --native-checks --plugin-sha256 <主包SHA256> --probe-sha256 <探针SHA256>

# 仅检查现代数据包路径，不能用它代替生命周期测试。
python E:/Minecraft-Server/incisionTest/devdump/sxrpg-e2e-matrix.py --servers 1.20.6 1.21.11 26.2-paper --backend SQLITE --packet-only --workers 3 --plugin-sha256 <主包SHA256> --probe-sha256 <探针SHA256>
```

远程后端通过 `--backend POSTGRESQL` / `REDIS` 及 `--credentials-json <本地受限文件>` 选择。Spigot 隔离需要 `--spigot-adyeshach-sha256 <核实的原JAR哈希>`。测试探针和 SX-Item 模板要单独准备；这些本机脚本不作为插件运行依赖。

通过证据要求所属 Java 进程正常退出、未强制结束，`server.properties`、`ops.json`、`usercache.json`、插件配置和临时原生模板逐字节恢复。仅启动成功、日志没有异常或客户端预测成功都不构成通过。扩展专项失败时保留已独立完成的核心断言，并保留失败原始记录。

测试 PostgreSQL 17.11 / Redis 8.10.1 为独立便携进程，仅监听回环 15432/16379，没有安装 Windows 服务。Redis 使用 AOF always/noeviction。凭据保存在 ACL 受限本地文件，不进源码和日志。此矩阵未验证断电、数据库崩溃或生产负载。

<!-- CLEANUP_STATUS_START -->
验收结束后已按精确哈希非递归归档 18 个测试专用文件：七份 Probe JAR、七份 SXRPGProbe.yml，以及仅为 Spigot/Leaf 添加的四份 ViaVersion/ViaBackwards JAR。SXRPGNative 临时模板已逐轮恢复为不存在。归档清单在 `sxrpg-matrix-results/cleanup-archive-20260907-173819-c926d6c0/cleanup-manifest.json`，`complete=true`；原路径均已撤下，归档副本哈希一致。

正式 SX 插件、PacketEvents、Vault、用户数据、原 Paper 26.2 的 Via 插件和 Spigot Adyeshach 均保留；32 个保留插件 JAR 与七份 RPG 配置清理前后哈希一致。配置均恢复原 SQLite，无测试模板或临时远程数据库依赖。所有本轮 Minecraft 进程正常结束；便携数据库通过所属运行目录的 STOP 请求正常关闭，`sxrpg-db-tools/run-20260907-143837-8f8bda55/stopped.json` 记录关闭时间，两个回环端口不再监听。数据、备份与失败/成功证据均保留。

两份独立结果汇总一致：本项目 `build/verification/server-summary.json` 与测试目录 `sxrpg-matrix-results/final-coverage.json`，分别确认 21/21 后端、7/7 原生槽、5/5 现代 PacketEvents。复验需要重新准备已归档的测试依赖，正式部署不需要它们。
<!-- CLEANUP_STATUS_END -->
