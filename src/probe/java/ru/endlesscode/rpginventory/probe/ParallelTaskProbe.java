package ru.endlesscode.rpginventory.probe;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Console-only observation of each plugin's relocated TabooLib startup tasks; no future is completed, cancelled or awaited. */
public final class ParallelTaskProbe {
    private ParallelTaskProbe() { }

    /** Inspect plugin-owned class loaders so unrelated TabooLib copies are never conflated. */
    public static void report(CommandSender sender) {
        Gson json = new Gson();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            Set<String> candidates = new LinkedHashSet<>();
            String prefix = plugin.getClass().getName();
            while (prefix.contains(".")) {
                prefix = prefix.substring(0, prefix.lastIndexOf('.'));
                candidates.add(prefix + ".taboolib.platform.bukkit.ParallelSystem");
            }
            candidates.add("taboolib.platform.bukkit.ParallelSystem");
            for (String candidate : candidates) {
                Class<?> type;
                try { type = Class.forName(candidate, false, plugin.getClass().getClassLoader()); }
                catch (ClassNotFoundException ignored) { continue; }
                catch (LinkageError failure) { sender.sendMessage("SX_RPG_PARALLEL error=" + plugin.getName() + ":" + failure.getClass().getSimpleName()); continue; }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("plugin", plugin.getName()); result.put("enabled", plugin.isEnabled()); result.put("class", candidate);
                result.put("classLoader", String.valueOf(type.getClassLoader()));
                try {
                    Object instance = type.getField("INSTANCE").get(null);
                    result.put("global", tasks(type.getMethod("getGlobalTaskMap").invoke(instance)));
                    result.put("local", tasks(type.getMethod("getLocalTaskMap").invoke(instance)));
                    Object running = type.getMethod("getRunningTask").invoke(instance);
                    result.put("running", running instanceof List ? ((List<?>) running).size() : "unknown");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    result.put("error", failure.getClass().getSimpleName() + ":" + failure.getMessage());
                }
                sender.sendMessage("SX_RPG_PARALLEL " + json.toJson(result));
                break;
            }
        }
        sender.sendMessage("SX_RPG_PARALLEL END");
    }

    private static List<Map<String, Object>> tasks(Object map) throws ReflectiveOperationException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(map instanceof Map)) return result;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", String.valueOf(entry.getKey()));
            Object value = entry.getValue();
            if (!(value instanceof CompletableFuture)) {
                Method getter = value.getClass().getMethod("getFuture");
                value = getter.invoke(value);
            }
            if (value instanceof CompletableFuture) {
                CompletableFuture<?> future = (CompletableFuture<?>) value;
                row.put("done", future.isDone()); row.put("cancelled", future.isCancelled());
                row.put("exceptional", future.isCompletedExceptionally());
            }
            result.add(row);
        }
        return result;
    }
}
