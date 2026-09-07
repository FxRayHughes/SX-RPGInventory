package ru.endlesscode.rpginventory.misc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.Log;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 检查实际 UTF-8 资源、默认语言及已有配置的回退边界，避免翻译破坏消息参数。 */
public class FileLanguageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** 缺省和空白配置都选中文，显式选择的其他语言仍然有效。 */
    @Test public void localeSelectionPreservesExplicitLanguages() {
        assertEquals("zh", FileLanguage.resolveLocale(null));
        assertEquals("zh", FileLanguage.resolveLocale("  "));
        assertEquals("en", FileLanguage.resolveLocale(" en "));
    }

    /** 所有中文示例必须可被 Bukkit 的实际 YAML 读取器解析。 */
    @Test public void bundledYamlParsesAndDefaultLanguageMatchesCode() throws Exception {
        for (String name : new String[]{"config.yml", "slots.yml", "items.yml", "backpacks.yml",
                "pets.yml", "pets.yml.legacy", "plugin.yml"}) {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStreamReader reader = reader(name)) {
                yaml.load(reader);
            }
            assertFalse(name, yaml.getKeys(false).isEmpty());
            if (name.equals("config.yml")) {
                assertEquals(FileLanguage.DEFAULT_LOCALE, yaml.getString("language"));
            }
        }
    }

    /** 与英文键集合和参数编号比对，防止漏译或因参数丢失造成运行时格式异常。 */
    @Test public void chineseMessagesCoverEnglishKeysAndArguments() throws Exception {
        Properties english = properties("lang/en.lang");
        Properties chinese = properties("lang/zh.lang");
        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames());
        for (String key : english.stringPropertyNames()) {
            String translated = chinese.getProperty(key);
            assertEquals(key, argumentIndexes(english.getProperty(key)), argumentIndexes(translated));
            new MessageFormat(translated).format(new Object[]{"示例", "参数"});
        }
    }

    /** 回退只补缺失键，自定义翻译及其磁盘内容不能被内置中文覆盖。 */
    @Test public void existingTranslationIsPreservedAndMissingKeysUseChinese() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path file = root.resolve("lang/en.lang");
        Files.createDirectories(file.getParent());
        byte[] original = "#version: 2.0\ntitle: Custom equipment\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        try (Fixture fixture = new Fixture(root, "en")) {
            FileLanguage language = new FileLanguage(fixture.plugin);
            assertEquals("Custom equipment", language.getMessage("title", true));
            assertEquals("装备数据正在加载，请稍后再试。", language.getMessage("error.player.loading", true));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    /** 不存在的语言在首次启动时也要创建目录并提供中文消息。 */
    @Test public void unavailableLocaleCreatesChineseFallbackFile() throws Exception {
        Path root = temporary.newFolder().toPath();
        try (Fixture fixture = new Fixture(root, "missing-locale")) {
            FileLanguage language = new FileLanguage(fixture.plugin);
            assertEquals("装备背包", language.getMessage("title", true));
            assertTrue(Files.exists(root.resolve("lang/missing-locale.lang")));
        }
    }

    /** 空语言文件应正常补齐，不应因缺失版本首行而中断插件启动。 */
    @Test public void emptyLocaleFileUsesChineseMessages() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.createDirectories(root.resolve("lang"));
        Files.write(root.resolve("lang/zh.lang"), new byte[0]);
        try (Fixture fixture = new Fixture(root, null)) {
            assertEquals("装备背包", new FileLanguage(fixture.plugin).getMessage("title", true));
        }
    }

    private static InputStreamReader reader(String name) {
        InputStream stream = FileLanguageTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(name, stream);
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }

    private static Properties properties(String name) throws Exception {
        Properties result = new Properties();
        try (InputStreamReader reader = reader(name)) {
            result.load(reader);
        }
        return result;
    }

    private static Set<String> argumentIndexes(String message) {
        Set<String> result = new HashSet<>();
        Matcher matcher = Pattern.compile("\\{(\\d+)(?:[},])").matcher(message);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    /** 仅模拟 Bukkit 插件文件入口；回退、Properties 读取和 MessageFormat 使用生产实现。 */
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Config> configuration = mockStatic(Config.class);
        final MockedStatic<Log> logging = mockStatic(Log.class);
        final RPGInventory plugin = mock(RPGInventory.class);

        Fixture(Path root, String locale) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("language", locale);
            configuration.when(Config::getConfig).thenReturn(config);
            when(plugin.getDataPath()).thenReturn(root);
            when(plugin.getResource(anyString())).thenAnswer(call ->
                    FileLanguageTest.class.getClassLoader().getResourceAsStream(call.getArgument(0)));
            doAnswer(call -> {
                String name = call.getArgument(0);
                try (InputStream stream = plugin.getResource(name)) {
                    if (stream == null) throw new IllegalArgumentException("Missing resource " + name);
                    Path destination = root.resolve(name);
                    Files.createDirectories(destination.getParent());
                    Files.copy(stream, destination);
                }
                return null;
            }).when(plugin).saveResource(anyString(), anyBoolean());
        }

        @Override public void close() {
            logging.close();
            configuration.close();
        }
    }
}
