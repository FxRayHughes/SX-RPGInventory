# SX-RPGInventory

基于 [EndlessCodeGroup/RPGInventory](https://github.com/EndlessCodeGroup/RPGInventory) 的现代化分支，为 SX-Attribute、SX-Item 提供 RPG 装备槽和便携背包，兼容目标覆盖 Paper 1.12.2、1.16.5、1.20.6、1.21.11、26.2、Spigot 26.1.2 和 Leaf 26.2。

当前为 `3.0.0-SNAPSHOT`。默认与 26.2 API 的 Gradle 构建各通过 73 项 Java 测试，无失败或跳过；七种服务端 × SQLite/PostgreSQL/Redis 的 21 组玩家生命周期、七服原生槽和现代五服 PacketEvents 专项均已通过。Spigot 26.1.2 的测试临时隔离了不支持该版本的 Adyeshach 2.1.30，不能据此声明原有全部插件组合兼容。具体验收范围见 [验证记录](docs/MODERNIZATION.md) 和 [实机矩阵](docs/SERVER-MATRIX.md)。

## 环境与依赖

- 构建使用 JDK 25；插件及内嵌运行库的普通类保持 Java 8 字节码。运行时使用对应服务端要求的 Java 版本（1.12.2 的 Java 8 至 26.x 的 Java 25）。
- Vault 及可用的权限提供者；付费槽位需要经济插件。
- SX-Attribute、SX-Item 为可选联动插件，需要支持目标 Paper 版本的发行版。
- 可选合成槽覆盖使用 PacketEvents 或 ProtocolLib：1.20.5+ 优先 PacketEvents 2.13.0，较旧服务端优先 ProtocolLib。二者都未安装时仍可使用装备和背包；数据包覆盖与自动合成保护一起停用。
- Mimic、PlaceholderAPI 可选。旧版 MyPet 桥默认排除，启用需要 `-PwithMyPet=true`、私有仓库凭据和独立兼容验证。

插件名为 `SX-RPGInventory`，数据目录为 `plugins/SX-RPGInventory`。保留 `ru.endlesscode.rpginventory` 公共包、`RPGInventory` 插件别名和 `rpginventory.*` 权限。不要同时安装上游插件。

## Gradle

采用标准 Java Library + Shadow，移除 BukkitGradle。Wrapper 固定为 Gradle 9.3.1，并配置官方分发包 SHA-256。IDE 直接导入项目根目录。

以下 Windows 命令只检查配置、依赖，不编译源码：

```powershell
$env:JAVA_HOME = 'D:/Java/jdk-25.0.1'
./gradlew.bat help --no-daemon
./gradlew.bat dependencies --configuration compileClasspath --no-daemon
```

构建并运行测试：

```powershell
./gradlew.bat clean build --no-daemon
./gradlew.bat clean build --no-daemon '-PpaperApiVersion=26.2.build.121-stable'
```

Linux/macOS 使用 `./gradlew`。默认 Paper API 为 `26.1.2.build.74-stable`。插件产物为 `build/libs/SX-RPGInventory-3.0.0-SNAPSHOT.jar`；`-plain.jar` 不包含运行库，不用于服务器安装。

真实 PostgreSQL / Redis 测试需要配置 [仓储测试环境变量](docs/STORAGE.md)；缺少变量时对应测试会跳过。实机诊断插件单独使用 `./gradlew.bat probeJar --no-daemon` 生成 `build/libs/SX-RPGInventory-Probe-1.0.0.jar`，不随主插件打包。

Windows 若出现 `UnixDomainSockets.connect0: Invalid argument`，在项目根目录使用较短的套接字目录后重试：

```powershell
New-Item -ItemType Directory -Path '.gradle/sockets' -Force | Out-Null
$socketPath = (Resolve-Path '.gradle/sockets').Path.Replace([char]92, [char]47)
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketPath"
```

此变量只用于当前终端。GitHub Actions 提供手动触发的双 API 工作流及临时 PostgreSQL / Redis 服务；本地通过不代表远端 CI 已执行。

## Maven publication

`api` publication 的坐标为 `github.saukiya.sxrpginventory:sx-rpginventory:3.0.0-SNAPSHOT`，发布普通薄 JAR 和源码。薄包本地产物为 `build/libs/SX-RPGInventory-api-3.0.0-SNAPSHOT.jar`；POM 保留真实运行时依赖，Bukkit 和可选服务器 API 标记为 provided。消费插件可使用 `compileOnly('github.saukiya.sxrpginventory:sx-rpginventory:3.0.0-SNAPSHOT') { transitive = false }` 并声明自己的目标 Bukkit API。

仅生成本地发布描述，不上传：

```powershell
./gradlew.bat apiJar sourcesJar generatePomFileForApiPublication generateMetadataFileForApiPublication --no-daemon
```

目标仓库通过用户 Gradle 属性 `mavenRepositoryUrl`、`mavenRepositoryUsername`、`mavenRepositoryPassword`，或环境变量 `SX_RPG_MAVEN_URL`、`SX_RPG_MAVEN_USERNAME`、`SX_RPG_MAVEN_PASSWORD` 配置。配置完成后，显式发布命令为：

```powershell
./gradlew.bat publishApiPublicationToSxRpgRepositoryRepository --no-daemon
```

常规 `build` 不执行发布；当前仅检查了本地 POM/module，尚未上传 Maven 仓库。

## 存储和 SX 联动

默认 SQLite，也可选择 PostgreSQL 或 Redis。玩家装备和背包共用所选后端，使用异步队列、条件写入、所有权租约和恢复日志。物品根据服务端能力使用 Paper 原生完整字节、现代 Spigot 的组件 NBT 或旧 CraftBukkit 的完整 NBT 格式；保留槽位空洞和自定义数据。部署、旧数据迁移及故障恢复见 [存储操作说明](docs/STORAGE.md)。

装备加载、点击和拖拽后刷新 SX-Attribute 已有的 RPG 数据源。物品匹配规则和纹理模板支持 `sxitem:<物品ID>`，通过 SX-Item 管理器识别、取得和更新物品。七服实际穿脱、原生槽同步、属性增减/去重、踢出重连与三后端重启恢复已通过；具体验收条件见服务器矩阵。

已采纳的上游建议、历史缺陷判断及 API 使用方式见 [上游 issue 处理记录](docs/UPSTREAM-ISSUES.md)。

## 来源与授权

继承上游 GPL-3.0-or-later 授权，保留 Git 历史和原作者版权声明。原作者包括 OsipXD / Osip Fatkullin、EndlessCode Group、FxRayHughes。详见 [LICENSE](LICENSE)；旧版历史保留在 [CHANGELOG.md](CHANGELOG.md)。
