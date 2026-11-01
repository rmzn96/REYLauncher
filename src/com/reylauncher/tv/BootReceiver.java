package com.reylauncher.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Optionally opens REYLauncher right after the TV boots (Settings → Advanced, off by default).
 * It does nothing else.
 */
public class BootReceiver extends BroadcastReceiver {
    public static final String KEY_LAUNCH_ON_BOOT = "launch_on_boot";

    public static boolean isLaunchOnBootEnabled(Context c) {
        if (c == null) return false;
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_LAUNCH_ON_BOOT, false);
    }

    public static void setLaunchOnBootEnabled(Context c, boolean enabled) {
        if (c == null) return;
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAUNCH_ON_BOOT, enabled)
                .apply();
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (c == null || i == null) return;
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) || !isLaunchOnBootEnabled(c)) return;
        try {
            Intent home = new Intent(c, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(home);
        } catch (Throwable ignored) { }
    }
}
