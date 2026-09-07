package ru.endlesscode.rpginventory.compat;

import ru.endlesscode.rpginventory.utils.Log;

/** Local diagnostics avoid an obsolete Plugin proxy and keep errors on the server's own logger. */
public final class PluginReporter {
    /** Preserve the subsystem context and full cause so failed initialization can be diagnosed from server logs. */
    public void report(String context, Exception cause) {
        Log.w(cause, context);
    }
}
