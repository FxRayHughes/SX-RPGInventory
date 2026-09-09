/*
 * This file is part of RPGInventory.
 * Copyright (C) 2018 EndlessCode Group and contributors
 *
 * RPGInventory is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RPGInventory is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RPGInventory.  If not, see <http://www.gnu.org/licenses/>.
 */

package ru.endlesscode.rpginventory.misc.config;

import org.bukkit.configuration.file.FileConfiguration;
import ru.endlesscode.rpginventory.inventory.slot.SlotManager;
import ru.endlesscode.rpginventory.utils.Version;

import java.util.Arrays;
import java.util.Collections;

/**
 * Created by OsipXD on 05.09.2015
 * It is part of the RpgInventory.
 * All rights reserved 2014 - 2016 © «EndlessCode Group»
 */
public class ConfigUpdater {
    /** 沿用历史版本迁移条件；新生成的展示示例使用中文，不额外改写现有用户配置。 */
    public static void update(Version configVersion) {
        FileConfiguration config = Config.getConfig();

        if (configVersion.compareTo("1.0.0") < 0) {
            config.set("ids", null);
            config.set("attack.auto-held", true);
        }

        if (configVersion.compareTo("1.0.1") < 0) {
            config.set("slots.pet", null);
            config.set("slots.crafting", null);
            config.set("slots.enabled", true);
            config.set("slots.level.spend", false);
            SlotManager.instance().saveDefaults();
        }

        if (configVersion.compareTo("1.1.0") < 0) {
            config.set("alternate-view.enabled", false);
            config.set("alternate-view.fill", "STAINED_GLASS_PANE:0");
        }

        if (configVersion.compareTo("1.0.4") < 0) {
            config.set("alternate-view.item", "ENCHANTED_BOOK");
            config.set("alternate-view.slot", 8);
            config.set("alternate-view.name", "&6装备");
            config.set("alternate-view.lore", "&7&o（右键打开装备界面）");
        }

        if (configVersion.compareTo("1.0.5") < 0) {
            if (config.getInt("alternate-view.slot") == 9) {
                config.set("alternate-view.slot", 8);
            }
            config.set("resource-pack.mode", config.getBoolean("alternate-view.enabled") ? "DISABLED" : "AUTO");
            config.set("resource-pack.url", "PUT_YOUR_URL_HERE");
            config.set("resource-pack.hash", "PUT_YOUR_HASH_HERE");
            config.set("alternate-view.enabled", null);
            config.set("alternate-view.use-item", true);
        }

        if (configVersion.compareTo("1.1.8") < 0) {
            config.set("alternate-view.enable-craft", true);
            config.set("worlds.mode", "BLACKLIST");
            config.set("worlds.list", new String[]{"blocked_world"});
        }

        if (configVersion.compareTo("1.2.4") < 0) {
            config.set("backpacks.expiration-time", 30);
        }

        if (configVersion.compareTo("1.2.5") < 0) {
            config.set("level-system", "EXP");
            config.set("class-system", "PERMISSIONS");
            config.set("metrics", true);
        }

        if (configVersion.compareTo("1.2.7") < 0) {
            config.set("items.lore-pattern", new String[]{
                    "_UNBREAKABLE_",
                    "_DROP_",
                    "_LEVEL_",
                    "_CLASS_",
                    "_SEPARATOR_",
                    "_LORE_",
                    "_SEPARATOR_",
                    "_SKILLS_",
                    "_SEPARATOR_",
                    "_STATS_"
            });
            config.set("items.separator", "");
            config.set("auto-update", false);
        }

        if (configVersion.compareTo("1.3.0") < 0) {
            config.set("auto-update", true);
        }

        if (configVersion.compareTo("1.3.3") < 0) {
            config.set("health.base", 20);
            config.set("health.scale", false);
            config.set("health.hearts", 20);
            config.set("health.heart-capacity.min", 1);
            config.set("health.heart-capacity.max", 5);
        }

        if (configVersion.compareTo("2.0.0") < 0) {
            // Removed RP settings
            config.set("resource-pack.fill", "DIAMOND_HOE:1");
            config.set("resource-pack.mode", null);
            config.set("alternate-view", null);
            config.set("containers.block", false);
            config.set("slots.locked", "DIAMOND_HOE:19");
            config.set("slots.buyable", "DIAMOND_HOE:18");

            // 仅在原有迁移分支生成中文示例；扩展 ID、权限与固定槽位编号保持兼容。
            config.set("craft.enabled", true);
            config.set("craft.extendable", "DIAMOND_HOE:0");
            config.set("craft.extensions.journeyman.name", "&a熟练工合成槽");
            config.set("craft.extensions.journeyman.lore", "&e获得熟练工权限后可使用这些槽位");
            config.set("craft.extensions.journeyman.slots", Arrays.asList(8, 9));
            config.set("craft.extensions.master.name", "&3大师合成槽");
            config.set("craft.extensions.master.lore", "&e获得大师权限后可使用这些槽位");
            config.set("craft.extensions.master.includes", Collections.singletonList("journeyman"));
            config.set("craft.extensions.master.slots", Arrays.asList(1, 4, 7));

            // 欢迎示例使用中文；%PLAYER% 保留旧版迁移使用的占位符协议。
            config.set("join-messages.enabled", true);
            config.set("join-messages.delay", 3);
            config.set("join-messages.default.title", "&l&2欢迎来到服务器！");
            config.set("join-messages.default.text", Arrays.asList("&6很高兴见到你，&3%PLAYER%", "&6本服使用 &9SX-RPGInventory &6管理装备与背包"));
            config.set("join-messages.rp-info.title", "&l&4资源包使用说明");
            config.set("join-messages.rp-info.text", Arrays.asList(
                    "&6请在服务器设置中&c允许&6使用资源包",
                    "&6资源包可正确显示装备槽位与物品贴图",
                    "&6如果此前拒绝了下载，可以重新调整设置",
                    "&6选择本服，点击&e“编辑”&6，将&e“服务器资源包”&6设为&e“启用”"));
        }

        if (configVersion.compareTo("2.0.1") < 0) {
            // 英文旧值是历史迁移的识别条件，必须保留，避免覆盖服主自定义标题。
            if ("&l&2Welcome to server!".equals(config.get("join-messages.rp-info.title"))) {
                config.set("join-messages.rp-info.title", "&l&4资源包使用说明");
                config.set("join-messages.default.title", "&l&2欢迎来到服务器！");
            }
        }

        if (configVersion.compareTo("2.0.4") < 0) {
            // Fixing join messages disabling
            config.set("join-messages.enabled", null);
            config.set("join-messages.rp-info.enabled", true);
            config.set("join-messages.default.enabled", true);

            // Added backpacks limit
            config.set("backpacks.limit", 1);

            // Added ability to disable extension for workbench
            config.set("craft.workbench", true);
            if ("DIAMOND_HOE:0".equals(config.get("craft.extendable"))) {
                config.set("craft.extendable", "DIAMOND_HOE:27");
            }
        }

        if (configVersion.compareTo("2.0.8") < 0) {
            config.set("fill", config.get("resource-pack.fill"));
            config.set("resource-pack.fill", null);
            config.set("resource-pack.enabled", true);
        }

        if (configVersion.compareTo("2.1.0") < 0) {
            config.set("resource-pack.delay", 2);
            config.set("check-update", config.getBoolean("auto-update"));
            config.set("auto-update", null);
        }

        if (configVersion.compareTo("2.1.4") < 0) {
            config.set("health", null);
        }

        if (configVersion.compareTo("2.2.0") < 0) {
            config.set("armor-slots-action", "default");
            config.set("craft-slots-action", "rpginv");
        }

        if (configVersion.compareTo("2.3.0") < 0) {
            config.set("metrics", null);
            config.set("containers", null);
        }

        if (configVersion.compareTo("2.3.2") < 0) {
            config.set("textures-type", "damage");
        }

        if (configVersion.compareTo("2.4.0") < 0) {
            config.set("level-system", null);
            config.set("class-system", null);
        }
    }
}
