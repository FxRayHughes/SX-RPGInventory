# 存储操作说明

## 后端选择

`storage.backend` 接受 `SQLITE`、`POSTGRESQL`、`REDIS`。共享装备的服务器使用相同后端、数据库和 namespace；无关服务器必须隔离 namespace。名称允许 1–64 个字母、数字、下划线或连字符。存储配置仅在插件启动时读取，修改后完整重启。

共享数据的节点还必须使用能相互读取物品格式的 Minecraft 版本。多版本兼容指同一个插件可在这些服务端运行，不代表 1.12 可以读取 26.x 的组件物品；混合世代的服务端应配置不同 namespace。原生数据修复只负责受支持的向新版本升级，不会将新物品静默降级。

SQLite 默认文件为 `plugins/SX-RPGInventory/storage/inventories.db`，使用 WAL、FULL 同步和单连接写入。适合单服；不要放到共享网络盘供多服打开。停服后备份完整目录；在线备份应使用 SQLite 一致性备份工具，不能只复制主文件而遗漏 WAL。

PostgreSQL 的数据库和具有建表、查询、写入权限的账号需预先准备：

```yaml
storage:
  backend: POSTGRESQL
  namespace: survival
  lease-seconds: 60
  postgresql:
    url: jdbc:postgresql://localhost:5432/minecraft
    username: minecraft
    password-env: SX_RPG_POSTGRES_PASSWORD
    pool-size: 4
```

环境变量由服务器进程继承。显式配置但缺少变量时启动失败，不回退空密码。固定表名为 `sx_rpginventory_records`，主键由 namespace 和 `player:<UUID>` / `backpack:<UUID>` 组成。

Redis 是独立主存储，当前不作为其他后端的缓存：

```yaml
storage:
  backend: REDIS
  namespace: survival
  lease-seconds: 60
  redis:
    uri-env: SX_RPG_REDIS_URI
```

变量为 `redis://...` 或 TLS 的 `rediss://...` URI。必须启用 AOF 和 `maxmemory-policy noeviction`；要求每次写入落盘时配置 `appendfsync always`，`everysec` 仍存在故障丢失窗口。命令成功不等于复制节点或磁盘已持久化。维护 AOF/备份及恢复演练，不对存储键设置 TTL 或执行缓存清理。

## 所有权和保存

每次加载取得独立 owner token，默认租约 60 秒，每四分之一周期续租和保存快照，最小租约为 15 秒。尚未加载或租约失效时冻结交互。CAS 写入检查 owner、revision 和租约，防止旧服务器覆盖新所有者。

正常队列上限为 1024。物品在主线程序列化，数据库和日志 I/O 在后台处理。退出排队保存并释放所有权；停服分别等待正常队列、恢复队列各最多 60 秒。超时不能视为保存成功，应检查日志。存储失败不会自动切换后端。

这些事务仅覆盖 RPG 装备和插件背包，不覆盖原版玩家背包、经济扣款或其他插件的跨服数据。

## 从上游迁移

1. 停服并备份旧 JAR、完整 `plugins/RPGInventory` 目录及世界玩家数据。
2. 确认旧 `.inv` / `.bp` 为上游 gzip YAML。古老二进制 NBT 应先在兼容旧服通过上游转换并保存；本分支不保证直接读取。
3. 在隔离副本中移除旧 JAR，将配置、`inventories` 和 `backpacks` 复制到 `plugins/SX-RPGInventory`，合并新 storage 配置。保留槽位名称、背包类型名称和 UUID，不同时运行两个插件。
4. 使用空目标后端启动。登录/打开背包时，取得租约后按需读取旧文件，成功保存之后才允许交互。旧文件保留；已有后端 payload 不会被旧文件覆盖。
5. 对账购买记录、装备数量、SX 物品 ID、NBT/PDC/组件和背包内容，重启后再核对。未知类型、缩小容量导致丢物或损坏记录应拒绝加载，不能直接删除记录解决。

当前没有跨后端自动搬迁工具。切换前须停服、备份并转换核验 payload、revision、键和 namespace；仅修改 backend 不会迁移数据。

## 恢复日志

每次后端保存前写入 `storage/recovery/*.recovery`，包含格式版本、key、owner、预期 revision 和 Base64 payload。日志强制写盘后原子改名；提交成功后删除。失败保留候选快照并冻结会话，队列拒绝时另行尝试后台日志写入。磁盘失败时日志也可能写入失败，断开玩家不等于保存成功。

日志不自动重放。处理故障时先停止相关服务器写入，备份数据库和所有日志。逐 key 比对后端 revision/payload 和日志：后端可能已提交但响应丢失，也可能已有新服务器写入。不能按文件时间无条件覆盖。

如果错误发生在物品序列化阶段，可能尚无日志字节。插件会冻结会话并保留内存中的玩家/背包对象，配置重载也会拒绝替换相关定义。这种情况应先隔离玩家并诊断失败物品、尝试导出内存数据，再决定是否停止进程；强制停服会丢失未能序列化的内存状态。保留对象不是持久化成功的保证。

在隔离副本解码核对，经人工确认后使用带版本条件的恢复操作；目前没有自动恢复命令。确认一致后再清理已处理日志并恢复服务。

## 集成测试

`RemoteRepositoryTest` 使用 `SX_RPG_TEST_POSTGRES_URL`、`SX_RPG_TEST_POSTGRES_USER`、`SX_RPG_TEST_POSTGRES_PASSWORD` 和 `SX_RPG_TEST_REDIS_URI`。务必使用可丢弃的独立测试数据库；随机 namespace 可能留下测试数据。缺少变量的测试会跳过，跳过不代表通过。
