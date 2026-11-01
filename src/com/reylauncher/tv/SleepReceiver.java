package com.reylauncher.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sleep support for Control Center: Power Off and the Sleep Timer put the TV to sleep through
 * REYLauncher's accessibility service (GLOBAL_ACTION_LOCK_SCREEN, which sleeps the device).
 * REYLauncher is not a device admin, so it can always be uninstalled normally.
 */
public class SleepReceiver extends BroadcastReceiver {

    static boolean canSleep(Context c) {
        return true;
    }

    /** Real sleep through the accessibility service; a black screen-off overlay if the service is off. */
    static boolean sleepNow(Context c) {
        if (PowerService.sleep()) {
            return true;
        }
        return ControlPanelOverlay.showScreenOff(c);
    }

    static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Schedules sleep in `minutes` (0 cancels). Survives the launcher being closed. */
    static void setTimer(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pending(c));
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        c.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putLong("sleep_at", at).apply();
        if (minutes > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    /** Minutes left on the sleep timer, or 0 when off. */
    static int timerLeft(Context c) {
        long at = c.getSharedPreferences("controls", Context.MODE_PRIVATE).getLong("sleep_at", 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? (int) Math.ceil(left / 60_000.0) : 0;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        c.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putLong("sleep_at", 0).apply();
        sleepNow(c);
    }
}
