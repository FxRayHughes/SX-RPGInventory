package ru.endlesscode.rpginventory.misc.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.utils.Version;

import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

/** 中文示例沿用原迁移边界，历史识别值和固定槽位协议必须保持兼容。 */
public class ConfigUpdaterLanguageTest {
    /** 旧配置新增示例使用中文，同时保留扩展 ID、槽位编号及旧占位符。 */
    @Test public void legacyUpgradeCreatesChineseExamplesWithoutChangingProtocol() {
        YamlConfiguration config = new YamlConfiguration();
        try (MockedStatic<Config> configuration = mockStatic(Config.class)) {
            configuration.when(Config::getConfig).thenReturn(config);
            ConfigUpdater.update(new Version(1, 3, 3));
            assertEquals("&a熟练工合成槽", config.getString("craft.extensions.journeyman.name"));
            assertEquals(Arrays.asList(8, 9), config.getIntegerList("craft.extensions.journeyman.slots"));
            assertEquals(Arrays.asList(1, 4, 7), config.getIntegerList("craft.extensions.master.slots"));
            assertEquals("&l&2欢迎来到服务器！", config.getString("join-messages.default.title"));
            assertTrue(config.getStringList("join-messages.default.text").get(0).contains("%PLAYER%"));
        }
    }

    /** 2.0.1 的历史修正仍识别英文旧标题，但不能改写同版本的服主自定义值。 */
    @Test public void historicalEnglishMatcherDoesNotOverwriteCustomTitle() {
        YamlConfiguration config = new YamlConfiguration();
        try (MockedStatic<Config> configuration = mockStatic(Config.class)) {
            configuration.when(Config::getConfig).thenReturn(config);
            config.set("join-messages.rp-info.title", "&l&2Welcome to server!");
            ConfigUpdater.update(new Version(2, 0, 0));
            assertEquals("&l&4资源包使用说明", config.getString("join-messages.rp-info.title"));
            config.set("join-messages.rp-info.title", "服主的说明");
            config.set("join-messages.default.title", "服主的欢迎语");
            ConfigUpdater.update(new Version(2, 0, 0));
            assertEquals("服主的说明", config.getString("join-messages.rp-info.title"));
            assertEquals("服主的欢迎语", config.getString("join-messages.default.title"));
        }
    }
}
