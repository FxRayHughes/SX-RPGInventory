/*
 * This file is part of RPGInventory.
 * Copyright (C) 2015-2017 Osip Fatkullin
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

package ru.endlesscode.rpginventory.misc;

import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.misc.config.Config;
import ru.endlesscode.rpginventory.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用 UTF-8 读取语言文件，并以中文补齐旧文件缺少的消息，保留服主已配置的翻译。 */
public class FileLanguage {
    /** 默认语言与内置资源文件名保持一致，缺省配置和缺失翻译共用同一个来源。 */
    public static final String DEFAULT_LOCALE = "zh";
    private final RPGInventory plugin;
    private final HashMap<String, MessageFormat> messageCache = new HashMap<>();
    private final Properties language = new Properties();
    @NotNull
    private final Path langFile;

    /** 只为不存在的文件写入默认内容；已有语言文件仍由服主维护。 */
    public FileLanguage(RPGInventory plugin) {
        this.plugin = plugin;
        String locale = resolveLocale(Config.getConfig().getString("language", DEFAULT_LOCALE));
        this.langFile = this.plugin.getDataPath().resolve(String.format("lang/%s.lang", locale));
        this.saveDefault();
        this.checkAndUpdateOutdatedLocaleFile();
        this.load();
        this.validateLocaleFile();
    }

    /** 空配置使用中文，但显式选择的其他语言不能被默认值覆盖。 */
    static String resolveLocale(String locale) {
        return locale == null || locale.trim().isEmpty() ? DEFAULT_LOCALE : locale.trim();
    }

    private void saveDefault() {
        if (Files.exists(this.langFile)) {
            return;
        }

        String path = "lang/" + this.langFile.getFileName();
        try {
            this.plugin.saveResource(path, true);
        } catch (Exception ex) {
            Log.w("无法载入语言文件 {0}：{1}；改用中文默认内容。", this.langFile.getFileName(), ex.toString());

            try (InputStream is = this.plugin.getResource("lang/" + DEFAULT_LOCALE + ".lang")) {
                Objects.requireNonNull(is);
                // 首次配置了不存在的语言时，saveResource 可能尚未创建 lang 目录。
                Files.createDirectories(this.langFile.getParent());
                Files.copy(is, Paths.get(this.langFile.toUri()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | NullPointerException e) {
                Log.s("无法将默认语言写入 {0}：{1}；将继续尝试使用内置消息。",
                        this.langFile.getFileName(), e.toString());
            }
        }
    }

    private void load() {
        try (InputStream is = Files.newInputStream(this.langFile);
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            this.language.load(isr);
        } catch (IOException e) {
            Log.s("无法读取语言文件：{0}；将继续尝试使用内置消息。", e.toString());
        }
    }

    // 兼容旧版 printf 参数语法；格式版本行必须保留，防止重复改写已有 MessageFormat 占位符。
    private void checkAndUpdateOutdatedLocaleFile() {
        final Path path = this.langFile;
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(e, "无法读取语言文件");
            return;
        }

        // 空文件也应正常回退到内置中文，而不是在读取首行时中断插件启用。
        if (!lines.isEmpty() && lines.get(0).startsWith("#version")) {
            return;
        }

        final Pattern pattern = Pattern.compile("%(s|d|.2f)");
        final LinkedList<String> newLines = new LinkedList<>();
        newLines.add("#version: 2.0 | 请勿删除此行，插件据此识别语言文件格式。");

        for (int i1 = 0; i1 < lines.size(); i1++) {
            String line = lines.get(i1);
            String newLine = line.replace("\"", "");
            Matcher m;
            for (int i = 0; (m = pattern.matcher(newLine)).find(); i++) {
                newLine = m.replaceFirst("{" + i + "}");
            }

            if (lines.size() > i1 + 1) {
                String nextLine = lines.get(i1 + 1);
                if ("\n ".length() < nextLine.length()
                        && (!nextLine.contains(":") || nextLine.indexOf(':') > nextLine.indexOf(' '))) {
                    newLine = newLine + "\\";
                }
            }

            newLines.add(newLine);
        }

        try {
            Files.write(
                    path, newLines, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            Log.w("无法保存语言文件：{0}", e.toString());
        }
    }

    private void validateLocaleFile() {
        Properties properties = new Properties();
        InputStream defaultLocale = this.plugin.getResource("lang/" + DEFAULT_LOCALE + ".lang");
        try (InputStreamReader isr = new InputStreamReader(Objects.requireNonNull(defaultLocale), StandardCharsets.UTF_8)) {
            properties.load(isr);
        } catch (IOException | NullPointerException e) {
            Log.w(e, "无法读取内置中文语言文件");
            // 内置资源不可用时保留已经载入的自定义消息，不覆盖现有翻译。
            return;
        }

        if (this.language.keySet().containsAll(properties.keySet())) {
            return;
        }

        for (Object key : properties.keySet()) {
            if (!this.language.containsKey(key)) {
                this.language.setProperty((String) key, properties.getProperty((String) key));
            }
        }
    }

    /** 兼容旧插件调用；新集成应使用 getMessage。 */
    @NotNull
    @Deprecated
    public String getCaption(String name, Object... args) {
        return this.getMessage(name, args);
    }

    /** 获取消息并保留颜色代码；缺失键会显示键名，便于修正配置。 */
    @NotNull
    public String getMessage(String key) {
        return this.getMessage(key, false);
    }

    /** 纯文本输出可移除颜色，消息内容与参数规则保持一致。 */
    @NotNull
    public String getMessage(String key, boolean stripColor) {
        return this.getMessage(key, stripColor, (Object[]) null);
    }

    /** 使用 MessageFormat 参数替换，语言文件中的 {0} 等参数编号必须保持兼容。 */
    @NotNull
    public String getMessage(String key, Object... args) {
        return this.getMessage(key, false, args);
    }

    /** 缓存格式模板以避免频繁解析；只处理显示文本，不改变传入参数。 */
    @NotNull
    public String getMessage(String key, boolean stripColor, Object... args) {
        if (!this.messageCache.containsKey(key)) {
            this.messageCache.put(key, new MessageFormat(
                    ChatColor.translateAlternateColorCodes(
                            '&', this.language.getProperty(key, "未知语言键：\"" + key + "\"")
                    )
            ));
        }
        String out = this.messageCache.get(key).format(args);
        return stripColor ? ChatColor.stripColor(out) : out;
    }
}
