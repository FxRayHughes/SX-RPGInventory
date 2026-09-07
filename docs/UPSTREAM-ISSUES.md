# 上游 issue 处理记录

2026-09-07 检查 EndlessCodeGroup/RPGInventory 全部 21 个未关闭 issue，并复查近期历史缺陷。表中的“实现”指本分支代码状态；实际验证范围见 [服务器矩阵](SERVER-MATRIX.md)，不等于上游 issue 已被关闭，也不代表支持未测试的第三方插件。

## 本轮采纳与修复

| 上游 issue | 判断与处理 | 验证方式 |
| --- | --- | --- |
| [#188](https://github.com/EndlessCodeGroup/RPGInventory/issues/188)、[#186](https://github.com/EndlessCodeGroup/RPGInventory/issues/186)、[#187](https://github.com/EndlessCodeGroup/RPGInventory/issues/187) | #188 的 Mimic 类提前链接与实机故障完全一致；隔离 Mimic、ProtocolLib、PacketEvents 等可选类，改为真正的 JavaPlugin 生命周期。版本请求由七端兼容矩阵覆盖，不宣称已测试这些 issue 所列的每个历史补丁版本。 | 七服均通过启动、完整 codec-storage、实际玩家存取和重启恢复；Spigot 的 Adyeshach 隔离限制见矩阵。 |
| [#155](https://github.com/EndlessCodeGroup/RPGInventory/issues/155)、[#27](https://github.com/EndlessCodeGroup/RPGInventory/issues/27) | 采纳装备事件建议：`RPGInventoryEquipEvent` 在变更提交后按玩家合并通知，提供脱离真实库存的前后快照及变更槽位。占位、信息、动作按钮不算装备；没有实际变化就不发事件。 | 快照隔离/位置变更单元测试；实际玩家装备流程。 |
| [#167](https://github.com/EndlessCodeGroup/RPGInventory/issues/167) | 采纳 ChestSort 集成：RPG 固定页面不可排序；玩家背包仅冻结保留槽和占位，普通储物格继续参与排序。未加载或失去租约时禁止排序。动态注册实际 ChestSortEvent，不增加硬依赖。 | 事件协议、固定槽/普通槽及加载状态测试。没有安装真实 ChestSort 时不宣称该第三方插件实服验收通过。 |
| [#162](https://github.com/EndlessCodeGroup/RPGInventory/issues/162) | 采纳 Maven publication 配置，导出普通 Java JAR 与源码及真实依赖元数据。服务器部署继续使用 Shadow JAR。 | 生成并检查本地 POM/module；未执行远端发布。 |
| [#180](https://github.com/EndlessCodeGroup/RPGInventory/issues/180) | 上游已修复多位数字范围。继续修复本分支 `sxitem:<ID>` 在 SlotManager 注册校验阶段被拒绝的问题；允许非空 SX ID，保留 ID 内命名空间冒号。 | 22 组规则参数化用例，包括多位范围、中文/命名空间 ID、空白/未知前缀。 |
| [#176](https://github.com/EndlessCodeGroup/RPGInventory/issues/176)、[#172](https://github.com/EndlessCodeGroup/RPGInventory/issues/172)、[#168](https://github.com/EndlessCodeGroup/RPGInventory/issues/168) | 上游已用取消 F 操作后重新发送副手修复客户端假复制。保留该修复，并将 `SWAP_OFFHAND` 改为名称比较，消除 1.12 每次点击触发的缺失枚举常量错误。 | 1.12 玩家点击与跨版本槽位操作回归。 |
| [#152](https://github.com/EndlessCodeGroup/RPGInventory/issues/152) | 当前 quit 路径会先关闭背包、同步取得最终内容，再排队保存并释放租约；未找到尚存的 kick 特有漏存根因。补充放物后立即 kick 的真实时序回归。 | 一次性测试玩家、提交后下一 tick kick、同背包 UUID 重连对账。 |
| [#10](https://github.com/EndlessCodeGroup/RPGInventory/issues/10) | 采纳数据库存储需求的方向，按当前任务提供 SQLite、PostgreSQL 和 Redis；没有把 PostgreSQL 标为 MySQL 支持。 | 真实三后端仓储测试及玩家保存/重启矩阵。 |

## 保留与未采纳的范围

- [#96](https://github.com/EndlessCodeGroup/RPGInventory/issues/96)：作者评论确认原版 2×2 合成与命令打开已经支持。继续保留 `craft-slots-action: default` 和 `/rpginv`；不额外抢占数字键或滚轮。
- [#163](https://github.com/EndlessCodeGroup/RPGInventory/issues/163)、[#173](https://github.com/EndlessCodeGroup/RPGInventory/issues/173)：本轮 SX 属性刷新与生命周期修复不能当作 MMOItems 镶嵌兼容证明。第三方只修改镜像装备视图、没有同步原版装备时仍可能回滚；未安装对应 MMOItems 版本，不盲目把整页反写到原版背包。使用第三方镶嵌时先卸下装备，或通过其正式 API 处理权威物品。
- [#185](https://github.com/EndlessCodeGroup/RPGInventory/issues/185)：批量 setter 涉及物品来源、置换返还、权限和持久化租约，不能用直接 `setContents` 作为安全装备事务。本轮先提供提交后的装备事件与现有只读 API，不添加会隐式销毁/复制物品的 setter。
- [#52](https://github.com/EndlessCodeGroup/RPGInventory/issues/52)、[#33](https://github.com/EndlessCodeGroup/RPGInventory/issues/33)：按槽权限及所有者绑定属于新的授权规则；当前背包 UUID 隔离与所有权租约不等于私人背包绑定。没有在升级时改变已有玩家装备的使用权。
- [#62](https://github.com/EndlessCodeGroup/RPGInventory/issues/62)：返回按钮不能直接占用已有存储格。本轮不缩减背包容量，也不覆盖已保存的最后一格。
- [#184](https://github.com/EndlessCodeGroup/RPGInventory/issues/184)、[#183](https://github.com/EndlessCodeGroup/RPGInventory/issues/183)、[#83](https://github.com/EndlessCodeGroup/RPGInventory/issues/83)、[#76](https://github.com/EndlessCodeGroup/RPGInventory/issues/76)：外观、MCPets、额外盔甲及 PvP 宠物规则需要相应插件和玩法约束，未加入当前 SX 存储与兼容改造。
- [#39](https://github.com/EndlessCodeGroup/RPGInventory/issues/39)、[#38](https://github.com/EndlessCodeGroup/RPGInventory/issues/38)：物品动作冷却和特殊头颅配置应结合 SX-Item 模板/动作能力实现，当前不再引入另一套动作与 NBT 约定。

## 装备事件与 Maven 消费

```java
@EventHandler
public void onEquipmentChanged(RPGInventoryEquipEvent event) {
    // 只读通知：槽位是 RPG 页的配置编号，快照修改不会改变玩家的真实物品。
    Set<Integer> changed = event.getChangedSlots();
    Map<Integer, ItemStack> items = event.getCurrentItems();
}
```

该事件不可取消；在原始 Bukkit 交互事件中校验和取消转移。在收到提交后的事件时读取最终装备，不需要再次监听全服每一个 InventoryClickEvent。

原版护甲、快捷栏及副手是对应镜像槽的权威数据源。刷新前先同步这些槽；放在普通 RPG 槽的饰品仍由 RPG 库存保存。点击、拖拽、换手、丢弃、拾取、装备使用和发射器换装进入延迟合并刷新，重载前排队的旧任务不会再次发布事件。

## 回归中发现的相关缺陷

- 多个快捷槽匹配同一掉落物时，上游拾取循环会把完整堆叠放入每一个空槽。真实监听器回归测试复现了 32 件变成 64 件；修复为首次成功提交后结束该次拾取。
- 1.12 实机背包放入后重新打开为空。服务端会为同一个原生库存创建新的 Bukkit 包装器，引用比较误判打开失败并提前释放存储会话。改用每次打开独有的 `BackpackHolder` 标识，保留页面被其他插件取消或重定向时的释放处理；ChestSort 的当前背包判断同步修正。回归覆盖包装器变化和重定向，实机复测结果见服务器矩阵。
- 1.20.6 实机装备后无法取下：占位物经过原生物品转换后，视觉标记的差异导致完整物品相等判断失败，占位物被交换到游标上。占位识别改为持久化类型及槽位标识；旧占位仅在完整模板匹配时兼容已知的 `HIDE_ATTRIBUTES` 差异，不使用名称或 Lore 作为身份凭据。
- 1.16.5 测试环境的 SkillAPI 启动失败后，SX-Attribute 仍因“插件存在”而调用缺失接口，阻断生命刷新。[SX-Attribute 配套修复](https://github.com/Saukiya/SX-Attribute/commit/db3de05)改为检查插件已启用，接口链接失败时只停用该可选来源；这不代表修复了失效的第三方 SkillAPI。

Maven 坐标为 `github.saukiya.sxrpginventory:sx-rpginventory:3.0.0-SNAPSHOT`。消费插件可使用 `compileOnly('github.saukiya.sxrpginventory:sx-rpginventory:3.0.0-SNAPSHOT') { transitive = false }` 并声明自己的目标 Bukkit API。发布仓库通过 `mavenRepositoryUrl` / `mavenRepositoryUsername` / `mavenRepositoryPassword` 或 `SX_RPG_MAVEN_URL` / `SX_RPG_MAVEN_USERNAME` / `SX_RPG_MAVEN_PASSWORD` 配置；这些配置不触发自动发布。
