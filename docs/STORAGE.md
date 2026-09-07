# 存储操作说明

## 后端选择

`storage.backend` 接受 `SQLITE`、`POSTGRESQL`、`MYSQL`、`REDIS`。共享装备的服务器使用相同后端、数据库和 namespace；无关服务器必须隔离 namespace。名称允许 1–64 个字母、数字、下划线或连字符，区分大小写。存储配置仅在插件启动时读取，修改后完整重启。

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

MySQL 8.x 的数据库和具有建表、查询、写入权限的账号同样需要预先准备：

```yaml
storage:
  backend: MYSQL
  namespace: survival
  lease-seconds: 60
  mysql:
    url: jdbc:mysql://localhost:3306/minecraft
    username: minecraft
    password-env: SX_RPG_MYSQL_PASSWORD
    pool-size: 4
```

Connector/J 8.4.0 随插件打包并兼容 Java 8。表固定使用 InnoDB 和 LONGBLOB；namespace、record_key、owner 使用 VARBINARY 和 UTF-8 字节绑定，因此不受数据库默认大小写不敏感排序规则或尾空格比较影响。若已有同名表采用不兼容的引擎或字段类型，启动会拒绝使用。连接 URL 可按实际环境配置 TLS；账号密码优先放在环境变量中。

连接参数必须保留 Connector/J 默认的 `useAffectedRows=false`；不支持改为 `true`。所有权判断依赖匹配行数，同一毫秒内重复获取或续租可能没有字段变化，按实际变更行数计数会把成功操作误判为冲突。

MySQL 的 `NOW()` / `CURRENT_TIMESTAMP` 固定在当前语句开始时，可能早于行锁等待结束。本实现先在事务内锁定记录，再用独立语句读取数据库毫秒时间，然后持锁执行 CAS 和租约判断。等待期间到期的保存/续租会被拒绝，过期后的新所有者可以接管。上线前应确认 InnoDB 持久化设置（例如 `innodb_flush_log_at_trx_commit=1`）、备份策略及 `max_allowed_packet` 能容纳最大的物品快照；LONGBLOB 的容量不意味着传输包限制会自动调整。

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

`RemoteRepositoryTest` 使用 `SX_RPG_TEST_POSTGRES_URL`、`SX_RPG_TEST_POSTGRES_USER`、`SX_RPG_TEST_POSTGRES_PASSWORD` 和 `SX_RPG_TEST_REDIS_URI`。`MysqlInventoryRepositoryTest` 使用 `SX_RPG_TEST_MYSQL_URL`、`SX_RPG_TEST_MYSQL_USER`、`SX_RPG_TEST_MYSQL_PASSWORD`。

MySQL 测试实际覆盖大于普通 BLOB 上限的二进制往返、连接池重开、namespace/owner 大小写及尾空格、并发 CAS 和 acquire/save/renew 的锁等待过期窗口。锁等待测试通过 `performance_schema.data_lock_waits` 与 `performance_schema.threads` 确认目标操作已经阻塞，因此测试账号除测试库权限外还需 `SELECT ON performance_schema.*`；正常插件账号不需要这项诊断权限。工作流仅给可丢弃的测试容器授予此权限。

务必使用可丢弃的独立测试数据库；随机 namespace 可能留下测试数据。缺少变量的测试会跳过，跳过不代表通过。新增 MySQL 测试在执行成功前不能视为已经验收。
