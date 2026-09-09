# 3.0.1 正式发布

发布日期：2026-09-07。版本统一为 `3.0.1`，发布入口为 [GitHub 正式版](https://github.com/FxRayHughes/SX-RPGInventory/releases/tag/v3.0.1)。安装使用 `SX-RPGInventory-3.0.1.jar`；`SX-RPGInventory-api-3.0.1.jar` 是供插件开发使用的薄包。

## 发布范围

本次将已验证的 Redis 缓存预发行版转为正式版本，更新 Gradle 版本、插件描述、产物名称及 API 坐标，不改变运行代码。收录 MySQL/SQLite/PostgreSQL 权威存储、Redis 可选缓存、中文默认配置、SX 联动及已有多版本兼容实现。

Redis 默认关闭，通过 `storage.redis.enabled: true` 开启；数据持久化和所有权仍由 SQL 负责。旧 `storage.backend: REDIS` 数据须停服备份、迁移核验到 SQL，不能直接切换空库。完整说明见 [STORAGE.md](STORAGE.md)。

## 核验结果

使用默认 Paper API `26.1.2.build.74-stable`、JDK 25 和 Gradle Wrapper 执行 `assemble generatePomFileForApiPublication generateMetadataFileForApiPublication`，构建成功。JAR 内插件版本、POM 及 Gradle module 坐标均为 `3.0.1`。

逐条目比较主包和 API 包与 `09c5d18` 缓存预发行版：文件集合完全相同，唯一内容差异为 `plugin.yml` 中的版本由 `3.0.0-SNAPSHOT` 更新为 `3.0.1`；所有类和其他资源字节一致。主包 4080 个普通类均不高于 Java 8。

本次未重复数据库集成测试和实机矩阵，沿用相同运行代码已经完成的两套 API 各 100 项测试、七服生命周期及 Redis 断线/离线重启/空缓存恢复验证。原始测试范围和证据见 [REDIS-CACHE.md](REDIS-CACHE.md)。Spigot 26.1.2 的实测仍以临时隔离不兼容的 Adyeshach 2.1.30 为条件，不代表该插件或原完整插件组合兼容。

| 产物 | SHA-256 |
| --- | --- |
| SX-RPGInventory-3.0.1.jar | `476b2de2d6cf97cf1b2895bcebf1538d6007d9c02d36522be2e2e4ace639770a` |
| SX-RPGInventory-api-3.0.1.jar | `7dfeecba7a22ad99938276853f31a2fb0b611489056c707ea11473e13dd62573` |

本地发布核验为 `build/release/3.0.1/verification.json`，附件提供 `SHA256SUMS.txt`。本次只发布 GitHub Release，未上传远端 Maven 仓库，也未替换上一轮测试目录中的插件。
