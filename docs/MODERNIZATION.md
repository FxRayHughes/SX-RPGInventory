# 改造和验证记录

来源：EndlessCodeGroup/RPGInventory 的 develop 分支。当前分支：`codex/sx-rpginventory-modern`。上游建议的逐项判断见 [UPSTREAM-ISSUES.md](UPSTREAM-ISSUES.md)。

后续 MySQL 与中文更新已完成两套 API 各 87 项测试，新增结果见 [MYSQL-CHINESE.md](MYSQL-CHINESE.md)。本页下方的 73 项测试、2265 个普通类及三后端数据是首版基线，保留用于追溯；当前包包含 MySQL 驱动，共 4079 个普通类，均不高于 Java 8。

## 实现范围

- 标准 Gradle 9.3.1 Wrapper、Shadow 9.3.2、JDK 25 工具链，生产普通类保持 Java 8 字节码；移除 BukkitGradle、Bintray 和旧 CI。提供薄 API 包、源码及 Maven 发布描述，常规构建不发布。
- SX-RPGInventory 插件标识，保留原公共 Java 包、权限和 RPGInventory 别名；隔离 Mimic、MyPet、PlaceholderAPI 及数据包库的可选类加载。
- SX-Item 模板、身份和更新桥；SX-Attribute 延迟合并刷新，先从玩家原生背包同步护甲、快捷栏和副手镜像，再更新属性。提供提交后的不可取消装备变化事件。
- 1.20.5+ 优先 PacketEvents，旧版优先 ProtocolLib；不可变主线程快照用于合成槽覆盖和自动配方保护，重载和停用释放监听器。
- SQLite、PostgreSQL、MySQL、Redis 玩家及背包持久化，异步生命周期、所有权租约、CAS、恢复日志和停服排空；完整原生物品编码与旧 gzip YAML 按需迁移。
- 可选 ChestSort 集成，保护固定及保留槽，允许普通储物格排序；未知类型、容量缩减及序列化失败时保留最后可用数据并冻结交互。

## 实测发现并修复

1. 多个快捷槽匹配同一掉落物时，真实监听器把 32 件复制为 64 件。首次成功拾取后结束该次处理，回归验证数量守恒。
2. 1.12 同一次打开背包可产生不同 CraftInventory 包装，引用比较误判打开失败并提前释放租约。改用每次打开独有的 BackpackHolder，仍拒绝取消或重定向的页面；单元测试与七服存取/重连回归覆盖此路径。
3. 1.20.6 原生物品投影丢失 HIDE_ATTRIBUTES，导致占位物无法识别、装备不能取下。共享键 `rpginventory_placeholder` 区分 `v1:slot:<配置名>`、`v1:fill`、`v1:buyable:<行号>`。旧模板仅归一化已证实的视觉标记差异，其他物品数据必须完整匹配；六项身份测试及七服实际穿脱通过。
4. 未安装 Mimic 时提前链接可选类、现代 InventoryView 调用差异、1.12 缺失 SWAP_OFFHAND 枚举、首次保存购买槽空值等兼容问题已修复。
5. 配套 SX-Attribute 修复旧服插件名识别，以及 SkillAPI 启动失败后仍调用缺失接口导致生命刷新中断的问题。该修复不代表修好了第三方 SkillAPI 本身。

## 构建验证

2026-09-07 默认 Paper API `26.1.2.build.74-stable` 与覆盖 API `26.2.build.121-stable` 均构建成功，各 **73 项测试，零失败、零错误、零跳过**，包括真实 PostgreSQL 17.11 与 Redis 8.10.1 的仓储所有权/CAS 测试。最终配置已恢复默认 API。两套 API 产出的主包及薄 API 包相同；后续诊断探针仅独立完成默认 API 的 probeJar 编译，生产主包保持不变。

Shadow 包的 2265 个普通类均不高于 Java 8，另有三个多版本类条目；JDBC 服务同时保留 PostgreSQL 和 SQLite 驱动。Maven POM/module 已生成并检查，未远端发布。SX-Attribute 配套构建的 15 项测试通过。

本地证据在 `build/verification/placeholders-default`、`placeholders-26.2`、`packet-slot-parallel-probe`，修复前的拾取失败在 `quick-pickup-before-fix`。这些构建目录证据会被 `clean` 删除；实服原始记录单独保存在测试根目录。远端 GitHub Actions 未执行。

## 实机验证

七种服务端的逐后端结果、产物哈希、原始证据及 Spigot 第三方插件隔离条件以 [SERVER-MATRIX.md](SERVER-MATRIX.md) 为准。测试包含实际客户端操作、物品数量及身份、SX 生命 20→27→20、背包存取、下一 tick kick、同 UUID 重连和完整 Minecraft 进程重启；启动成功与单纯仓储测试不会替代这些断言。

## 验证边界

实服矩阵只覆盖记录中的七种具体服务端构建及操作；不等于所有历史补丁、第三方插件组合或生产负载均已通过。ChestSort 已做 API 事件协议测试，未安装真实插件实测；MMOItems 镶嵌、MyPet、旧资源包纹理、真实旧存档迁移、跨世界/死亡全流程及故障注入不是本次完整验收范围。

多版本运行兼容不代表旧服可读取新版本组件物品；跨后端迁移没有自动工具，古老二进制 NBT 需要旧服预转换。存储事务不覆盖原版玩家背包、经济或其他插件，恢复日志不自动重放，Redis 磁盘持久性取决于服务端配置。未声明 Folia 支持。操作约束见 [STORAGE.md](STORAGE.md)。
