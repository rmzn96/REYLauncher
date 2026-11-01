package com.reylauncher.tv;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;

/**
 * Pure-Java local ADB client (127.0.0.1:5555) for granting REYLauncher's system permissions
 * automatically during Easy Setup when ADB / Network Debugging is enabled on the TV.
 */
final class LocalAdb {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    // ASN.1 DigestInfo prefix for SHA-1 (15 bytes) + 20-byte SHA-1 token = 35 bytes
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[]{
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    interface Callback {
        void onResult(boolean success, String message);
    }

    static volatile boolean portReachable = false;
    private static final android.os.Handler KEEP_ALIVE_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Context keepAliveAppCtx;
    private static boolean keepAliveWatchdogScheduled = false;
    private static volatile boolean repairInProgress = false;
    private static volatile long lastRepairAttemptMs = 0L;
    private static final long KEEP_ALIVE_INTERVAL_MS = 60_000L;
    private static final long REPAIR_COOLDOWN_MS = 25_000L;

    private static final Runnable KEEP_ALIVE_TICK = new Runnable() {
        @Override
        public void run() {
            KEEP_ALIVE_HANDLER.removeCallbacks(this);
            if (keepAliveAppCtx == null || !isKeepAliveEnabled(keepAliveAppCtx)) {
                keepAliveWatchdogScheduled = false;
                return;
            }
            try {
                android.os.PowerManager pm = keepAliveAppCtx.getSystemService(android.os.PowerManager.class);
                if (pm == null || pm.isInteractive()) {
                    checkAndRepairPermissionsIfNeeded(keepAliveAppCtx, false);
                }
            } catch (Throwable ignored) {
            }
            KEEP_ALIVE_HANDLER.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
        }
    };

    private LocalAdb() { }

    static boolean isPaired(Context c) {
        return c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).getBoolean("paired", false);
    }

    private static void setPaired(Context c, boolean val) {
        c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).edit().putBoolean("paired", val).apply();
    }

    static boolean isKeepAliveEnabled(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("adb_state", Context.MODE_PRIVATE);
        if (!sp.getBoolean("keep_alive_default_on", false)) {
            sp.edit()
                    .putBoolean("keep_alive_default_on", true)
                    .putBoolean("keep_alive", true)
                    .apply();
        }
        return sp.getBoolean("keep_alive", true);
    }

    static void setKeepAliveEnabled(Context c, boolean enabled) {
        c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).edit().putBoolean("keep_alive", enabled).apply();
        if (enabled) {
            ensureKeepAliveWatchdog(c);
        } else {
            KEEP_ALIVE_HANDLER.removeCallbacks(KEEP_ALIVE_TICK);
            keepAliveWatchdogScheduled = false;
        }
    }

    static void ensureKeepAliveWatchdog(Context c) {
        if (c == null) return;
        keepAliveAppCtx = c.getApplicationContext();
        if (!isKeepAliveEnabled(keepAliveAppCtx)) return;
        if (!keepAliveWatchdogScheduled) {
            keepAliveWatchdogScheduled = true;
            KEEP_ALIVE_HANDLER.removeCallbacks(KEEP_ALIVE_TICK);
            KEEP_ALIVE_HANDLER.postDelayed(KEEP_ALIVE_TICK, 5_000L);
        }
    }

    private static final Runnable REPAIR_RUNNABLE = () -> {
        Context ctx = keepAliveAppCtx;
        if (ctx != null) checkAndRepairPermissionsIfNeeded(ctx, false);
    };

    static void scheduleKeepAliveRepair(Context c, long delayMs) {
        if (c == null) return;
        final Context appCtx = c.getApplicationContext();
        keepAliveAppCtx = appCtx;
        closeIfIdle(appCtx);
        if (!isKeepAliveEnabled(appCtx)) return;
        // Only this runnable is replaced: the auto-close timer and the watchdog tick stay scheduled.
        KEEP_ALIVE_HANDLER.removeCallbacks(REPAIR_RUNNABLE);
        KEEP_ALIVE_HANDLER.postDelayed(REPAIR_RUNNABLE, Math.max(8_000L, delayMs));
    }

    /**
     * Checks whether a known conflicting firmware AccessibilityService (such as Wanbo's
     * {@code com.sdt.projector.observer/com.sdt.hdmi.MyAccessibilityService}, which swallows all
     * D-Pad, OK, and Back key events in its {@code onKeyEvent}) is currently enabled alongside ours.
     */
    static boolean hasConflictingAccessibilityService(Context c) {
        if (c == null) return false;
        try {
            String existingAcc = Settings.Secure.getString(
                    c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (existingAcc != null && (existingAcc.contains("com.sdt.hdmi.MyAccessibilityService")
                    || existingAcc.contains("com.sdt.projector.observer"))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static String buildCleanAccessibilityServicesList(Context c, String existingAcc) {
        String pkg = c.getPackageName();
        String ourAcc = pkg + "/" + pkg + ".PowerService";
        String ourShort = pkg + "/.PowerService";
        StringBuilder sb = new StringBuilder();
        if (existingAcc != null && !existingAcc.trim().isEmpty()) {
            for (String part : existingAcc.split(":")) {
                String trimmed = part == null ? "" : part.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equals(ourAcc) || trimmed.equals(ourShort)) continue;
                // Exclude Wanbo HDMI observer accessibility service which intercepts & drops all remote keys
                if (trimmed.contains("com.sdt.hdmi.MyAccessibilityService")
                        || trimmed.contains("com.sdt.projector.observer")) {
                    continue;
                }
                if (sb.length() > 0) sb.append(':');
                sb.append(trimmed);
            }
        }
        if (sb.length() > 0) sb.append(':');
        sb.append(ourAcc);
        return sb.toString();
    }

    /**
     * Lightweight, zero-allocation permission status check for background Keep Alive.
     * Only checks if the Accessibility Service setting was turned off by the TV OS or polluted
     * by a conflicting HDMI service. Never triggers background loops for Default Home or Notification Listener.
     */
    static boolean needsPermissionRepair(Context c) {
        if (c == null) return false;
        if (!ButtonMapper.isServiceEnabled(c) || hasConflictingAccessibilityService(c)) {
            return true;
        }
        return false;
    }

    static void checkAndRepairPermissionsIfNeeded(Context c, boolean forceNow) {
        if (c == null) return;
        final Context appCtx = c.getApplicationContext();
        closeIfIdle(appCtx);
        if (!forceNow && !isKeepAliveEnabled(appCtx)) return;
        // Automatic repairs only in the first 10 minutes after the box starts (when TV firmware tends to
        // switch accessibility services off). Switching the service off yourself later is respected.
        if (!forceNow && android.os.SystemClock.elapsedRealtime() > 10 * 60_000L) return;
        if (repairInProgress) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (lastRepairAttemptMs > 0L && (now - lastRepairAttemptMs) < REPAIR_COOLDOWN_MS) {
            return;
        }
        long epochNow = System.currentTimeMillis();
        android.content.SharedPreferences sp = appCtx.getSharedPreferences("adb_state", Context.MODE_PRIVATE);
        long lastEpoch = sp.getLong("last_repair_epoch", 0L);
        if (!forceNow && lastEpoch > 0L && (epochNow - lastEpoch) < 120_000L) {
            return;
        }
        if (!needsPermissionRepair(appCtx)) {
            return;
        }
        repairInProgress = true;
        lastRepairAttemptMs = now;
        sp.edit().putLong("last_repair_epoch", epochNow).apply();
        repairAccessibilityOnly(appCtx, (success, msg) -> {
            repairInProgress = false;
        });
    }

    /**
     * Turns REYLauncher's accessibility switch back on when the TV switched it off, using the
     * WRITE_SECURE_SETTINGS permission granted once during setup. No ADB connection is used.
     */
    private static void repairAccessibilityOnly(Context c, Callback cb) {
        boolean ok = false;
        if (canWriteSecureSettings(c)) {
            try {
                String existingAcc = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
                ok = Settings.Secure.putString(c.getContentResolver(), "enabled_accessibility_services",
                        buildCleanAccessibilityServicesList(c, existingAcc));
                Settings.Secure.putInt(c.getContentResolver(), "accessibility_enabled", 1);
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        if (cb != null) cb.onResult(ok, ok ? null : "NO_SECURE_SETTINGS");
    }

    static boolean canWriteSecureSettings(Context c) {
        return c != null && c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ---- ADB is only switched on for a task (setup, CleanYourTV) and switched off again after it ----

    private static final String K_AUTO_CLOSE = "auto_close_adb";
    private static final String K_WE_USED_ADB = "we_used_adb";
    private static final long AUTO_CLOSE_AFTER_MS = 3 * 60_000L;
    private static volatile long lastAdbUseMs = 0L;  // elapsedRealtime of the last ADB activity
    private static final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean holdOpen;  // Settings is on screen (e.g. a CleanYourTV checklist)
    private static Context autoCloseCtx;
    private static final Runnable AUTO_CLOSE = () -> {
        Context ctx = autoCloseCtx;
        if (ctx != null) closeIfIdle(ctx);
    };

    static boolean isAutoCloseEnabled(Context c) {
        return c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).getBoolean(K_AUTO_CLOSE, true);
    }

    static void setAutoCloseEnabled(Context c, boolean on) {
        c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).edit().putBoolean(K_AUTO_CLOSE, on).apply();
        if (on) closeIfIdle(c);
    }

    /**
     * Called after a successful ADB login and after every command: remembers that REYLauncher opened
     * ADB, and (re)schedules switching it off a few minutes after the last activity.
     */
    private static void markAdbUsed(Context c) {
        Context appCtx = c.getApplicationContext();
        autoCloseCtx = appCtx;
        lastAdbUseMs = android.os.SystemClock.elapsedRealtime();
        appCtx.getSharedPreferences("adb_state", Context.MODE_PRIVATE).edit().putBoolean(K_WE_USED_ADB, true).apply();
        KEEP_ALIVE_HANDLER.removeCallbacks(AUTO_CLOSE);
        KEEP_ALIVE_HANDLER.postDelayed(AUTO_CLOSE, AUTO_CLOSE_AFTER_MS + 1_000L);
    }

    private static boolean weUsedAdb(Context c) {
        return c.getSharedPreferences("adb_state", Context.MODE_PRIVATE).getBoolean(K_WE_USED_ADB, false);
    }

    /** Settings keeps ADB from closing while it is on screen; leaving it restarts the 3-minute timer. */
    static void setHoldOpen(Context c, boolean hold) {
        holdOpen = hold;
        if (!hold && c != null && weUsedAdb(c.getApplicationContext())) markAdbUsed(c);
    }

    /** The screen went off: switch ADB off now if REYLauncher opened it (and nothing is running). */
    static void closeOnScreenOff(Context c) {
        if (c == null) return;
        Context appCtx = c.getApplicationContext();
        if (!isAutoCloseEnabled(appCtx) || !weUsedAdb(appCtx) || inFlight.get() > 0) return;
        closeAdbNow(appCtx, null);
    }

    /** Switches ADB off when REYLauncher used it and nothing has used it for a few minutes. */
    static void closeIfIdle(Context c) {
        if (c == null) return;
        Context appCtx = c.getApplicationContext();
        if (!isAutoCloseEnabled(appCtx) || !weUsedAdb(appCtx)) return;
        if (inFlight.get() > 0 || holdOpen) return;  // an ADB task is running, or Settings is open
        long idle = android.os.SystemClock.elapsedRealtime() - lastAdbUseMs;
        if (lastAdbUseMs != 0L && idle < AUTO_CLOSE_AFTER_MS) return;
        closeAdbNow(appCtx, null);
    }

    /**
     * Turns ADB (USB and network debugging, port 5555) off and deletes REYLauncher's ADB key, so the
     * next ADB task asks "Allow debugging?" on the TV again. Uses WRITE_SECURE_SETTINGS when granted,
     * otherwise one last shell command over the open connection.
     */
    static void closeAdbNow(Context c, Callback cb) {
        final Context appCtx = c.getApplicationContext();
        if (!closing.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(false, "BUSY");
            return;
        }
        new Thread(() -> {
            try {
                boolean viaSettings = false;
                if (canWriteSecureSettings(appCtx)) {
                    try {
                        Settings.Global.putInt(appCtx.getContentResolver(), "adb_wifi_enabled", 0);
                    } catch (Throwable ignored) { }
                    try {
                        viaSettings = Settings.Global.putInt(appCtx.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                    } catch (Throwable ignored) { }
                }
                if (!viaSettings) {
                    // Never open a new ADB connection just to close ADB (that would pop up another
                    // "Allow debugging?"). Without the permission, the user turns it off in Developer options.
                    if (cb != null) cb.onResult(false, "NO_SECURE_SETTINGS");
                    return;
                }
                KEEP_ALIVE_HANDLER.removeCallbacks(AUTO_CLOSE);
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException ignored) { }
                boolean stillOpen = isPortOpen("127.0.0.1", 5555, 500);
                portReachable = stillOpen;
                forgetKey(appCtx);
                appCtx.getSharedPreferences("adb_state", Context.MODE_PRIVATE).edit().putBoolean(K_WE_USED_ADB, false).apply();
                lastAdbUseMs = 0L;
                if (cb != null) cb.onResult(!stillOpen, stillOpen ? "PORT_STILL_OPEN" : null);
            } finally {
                closing.set(false);
            }
        }, "local-adb-close").start();
    }

    /** Deletes REYLauncher's ADB key pair; a new one (and a new "Allow debugging?" prompt) is made on next use. */
    static void forgetKey(Context c) {
        cachedKeyPair = null;
        try {
            new File(c.getFilesDir(), "adb_rsa.priv").delete();
            new File(c.getFilesDir(), "adb_rsa.pub").delete();
        } catch (Throwable ignored) { }
        setPaired(c, false);
    }

    static void probePortAsync(Context c, Runnable onDone) {
        new Thread(() -> {
            String[] hosts = {"127.0.0.1"};
            boolean open = false;
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (isPortOpen(h, 5555, 500)) {
                    open = true;
                    break;
                }
            }
            portReachable = open;
            if (onDone != null) onDone.run();
        }, "adb-probe").start();
    }

    static String getStatusLabel(Context c) {
        boolean adbOn = isAdbSettingEnabled(c);
        boolean paired = isPaired(c);
        if (portReachable && paired) {
            return I18n.s(c, "Connected & Paired (Port 5555)", "Bağlı ve Eşleşti (Port 5555)");
        }
        if (portReachable) {
            return I18n.s(c, "Port 5555 Open · Ready to Pair", "Port 5555 Açık · Eşleşmeye Hazır");
        }
        if (adbOn && paired) {
            return I18n.s(c, "Paired · ADB Enabled", "Eşleşti · ADB Açık");
        }
        if (adbOn) {
            return I18n.s(c, "ADB Enabled · Not Paired", "ADB Açık · Eşleşmedi");
        }
        return I18n.s(c, "Disconnected (ADB Off)", "Bağlı Değil (ADB Kapalı)");
    }

    static boolean isAdbSettingEnabled(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void grantAllPermissions(Context c, Callback cb) {
        new Thread(() -> {
            String pkg = c.getPackageName();
            List<String> cmds = new ArrayList<>();

            // 1. Lets REYLauncher re-enable its own accessibility switch and turn ADB off again
            //    later without any ADB connection (Settings.Secure / Settings.Global writes).
            cmds.add("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS 2>/dev/null");

            // 2. Storage permissions (only if not already granted — avoids Android 11/12 StorageManagerService killUid loop!)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (!android.os.Environment.isExternalStorageManager()) {
                        cmds.add("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow 2>/dev/null");
                    }
                } else {
                    if (c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        cmds.add("pm grant " + pkg + " android.permission.READ_EXTERNAL_STORAGE 2>/dev/null");
                        cmds.add("pm grant " + pkg + " android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null");
                    }
                }
            } catch (Throwable ignored) {
            }

            cmds.add("appops set " + pkg + " GET_USAGE_STATS allow 2>/dev/null");
            cmds.add("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null");

            // 3. Notification Listener (only if not already granted)
            if (!MediaWatchService.granted(c)) {
                cmds.add("cmd notification allow_listener " + pkg + "/" + pkg + ".MediaWatchService 2>/dev/null");
            }

            // 5. Accessibility Service (never toggle accessibility_enabled 0, only update if needed)
            if (!ButtonMapper.isServiceEnabled(c) || hasConflictingAccessibilityService(c)) {
                String existingAcc = "";
                try {
                    existingAcc = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
                } catch (Exception ignored) {
                }
                String newAcc = buildCleanAccessibilityServicesList(c, existingAcc);
                cmds.add("settings put secure enabled_accessibility_services '" + newAcc + "'");
                cmds.add("settings put secure accessibility_enabled 1");
            }

            // 6. Default Home Role + Preferred Home Activity
            cmds.add("cmd role add-role-holder --user 0 android.app.role.HOME " + pkg + " 2>/dev/null");
            cmds.add("cmd package set-home-activity --user 0 " + pkg + "/.MainActivity 2>/dev/null");
            cmds.add("cmd package set-home-activity " + pkg + "/.MainActivity 2>/dev/null");

            String[] hosts = {"127.0.0.1"};
            Exception lastErr = null;
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (!isPortOpen(h, 5555, 800)) continue;
                try {
                    runShellCommandsOnHost(c, h, 5555, cmds, null);
                    portReachable = true;
                    setPaired(c, true);
                    if (cb != null) cb.onResult(true, null);
                    return;
                } catch (Exception e) {
                    lastErr = e;
                }
            }

            if (cb != null) {
                if (lastErr != null) {
                    cb.onResult(false, lastErr.getMessage());
                } else {
                    cb.onResult(false, "PORT_CLOSED");
                }
            }
        }, "local-adb").start();
    }

    static void setDefaultLauncherViaAdb(Context c, Callback cb) {
        new Thread(() -> {
            String pkg = c.getPackageName();
            List<String> cmds = new ArrayList<>();
            cmds.add("cmd role add-role-holder --user 0 android.app.role.HOME " + pkg + " 2>/dev/null");
            cmds.add("cmd package set-home-activity --user 0 " + pkg + "/.MainActivity 2>/dev/null");
            cmds.add("cmd package set-home-activity " + pkg + "/.MainActivity 2>/dev/null");

            String[] hosts = {"127.0.0.1"};
            Exception lastErr = null;
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (!isPortOpen(h, 5555, 800)) continue;
                try {
                    runShellCommandsOnHost(c, h, 5555, cmds, null);
                    portReachable = true;
                    setPaired(c, true);
                    if (cb != null) cb.onResult(true, null);
                    return;
                } catch (Exception e) {
                    lastErr = e;
                }
            }
            if (cb != null) {
                cb.onResult(false, lastErr != null ? lastErr.getMessage() : "PORT_CLOSED");
            }
        }, "local-adb-home").start();
    }

    /**
     * Revokes Accessibility, Notification Listener, Default Home and the ADB-granted permissions.
     */
    static void revokeAllPermissions(Context c, Callback cb) {
        if (c == null) {
            if (cb != null) cb.onResult(false, "NO_CONTEXT");
            return;
        }
        final Context appCtx = c.getApplicationContext();
        // 1. Disable Keep Alive immediately so it does not re-grant permissions
        setKeepAliveEnabled(appCtx, false);

        // 2. Revoke Device Admin and Accessibility directly via Android APIs (works even without ADB!)
        try {
            SleepReceiver.setTimer(appCtx, 0);
        } catch (Throwable ignored) { }
        try {
            if (PowerService.instance != null && android.os.Build.VERSION.SDK_INT >= 24) {
                PowerService.instance.disableSelf();
            }
        } catch (Throwable ignored) { }
        try {
            appCtx.getPackageManager().clearPackagePreferredActivities(appCtx.getPackageName());
        } catch (Throwable ignored) { }

        // 3. Also run ADB revoke commands if Local ADB is reachable
        new Thread(() -> {
            String pkg = appCtx.getPackageName();
            String existingAcc = "";
            try {
                existingAcc = Settings.Secure.getString(appCtx.getContentResolver(), "enabled_accessibility_services");
            } catch (Exception ignored) { }
            String ourAcc = pkg + "/" + pkg + ".PowerService";
            String ourShort = pkg + "/.PowerService";
            StringBuilder cleanAcc = new StringBuilder();
            if (existingAcc != null && !existingAcc.trim().isEmpty()) {
                for (String part : existingAcc.split(":")) {
                    String trimmed = part == null ? "" : part.trim();
                    if (trimmed.isEmpty() || trimmed.equals(ourAcc) || trimmed.equals(ourShort)) continue;
                    if (cleanAcc.length() > 0) cleanAcc.append(':');
                    cleanAcc.append(trimmed);
                }
            }

            List<String> cmds = new ArrayList<>();
            cmds.add("settings put secure enabled_accessibility_services '" + cleanAcc + "'");
            cmds.add("cmd notification disallow_listener " + pkg + "/" + pkg + ".MediaWatchService 2>/dev/null");
            cmds.add("cmd role remove-role-holder --user 0 android.app.role.HOME " + pkg + " 2>/dev/null");
            cmds.add("cmd package clear-package-preferred-activities " + pkg + " 2>/dev/null");
            cmds.add("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE default 2>/dev/null");
            cmds.add("appops set " + pkg + " GET_USAGE_STATS default 2>/dev/null");

            String[] hosts = {"127.0.0.1"};
            boolean adbRan = false;
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (!isPortOpen(h, 5555, 600)) continue;
                try {
                    runShellCommandsOnHost(appCtx, h, 5555, cmds, null);
                    adbRan = true;
                    break;
                } catch (Exception ignored) { }
            }
            closeAdbNow(appCtx, null);
            if (cb != null) cb.onResult(true, adbRan ? "ADB_AND_LOCAL" : "LOCAL_ONLY");
        }, "local-adb-revoke").start();
    }

    static void forceStopPackagesAsync(Context c, java.util.List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty() || !isPaired(c)) return;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String p : pkgs) {
                if (p == null || p.isEmpty() || p.equals(c.getPackageName())) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append("am force-stop ").append(p);
            }
            if (sb.length() == 0) return;
            String[] hosts = {"127.0.0.1"};
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (!isPortOpen(h, 5555, 400)) continue;
                try {
                    runShellCommand(c, h, 5555, sb.toString());
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "local-adb-forcestop").start();
    }

    static boolean startActivityViaAdbAsync(Context c, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || !isPaired(c)) return false;
        new Thread(() -> {
            if (!pkg.matches("[A-Za-z0-9_.]+") || (cls != null && !cls.matches("[A-Za-z0-9_.$]*"))) return;
            String cmd = (cls != null && !cls.isEmpty())
                    ? ("am start -n '" + pkg + "/" + cls + "'")
                    : ("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
            String[] hosts = {"127.0.0.1"};
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                if (!isPortOpen(h, 5555, 400)) continue;
                try {
                    runShellCommand(c, h, 5555, cmd);
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "local-adb-start").start();
        return true;
    }

    interface BatchProgressListener {
        void onStep(int index, int total, String cmd, String output);
    }

    static String execShellOutput(Context c, String shellCmd) throws Exception {
        String[] hosts = {"127.0.0.1"};
        Exception lastErr = null;
        for (String h : hosts) {
            if (h == null || h.isEmpty()) continue;
            if (!isPortOpen(h, 5555, 600)) continue;
            try {
                String out = runShellCommandOutput(c, h, 5555, shellCmd);
                portReachable = true;
                setPaired(c, true);
                return out;
            } catch (Exception e) {
                lastErr = e;
            }
        }
        if (lastErr != null) throw lastErr;
        throw new Exception("PORT_CLOSED");
    }

    /**
     * Runs each shell command in its own separate `shell:` stream over a single authenticated ADB socket
     * (matching CleanYourTV's rule: "Run pm commands one per shell call, chained commands may silently stop after the first").
     */
    static boolean execShellSequential(Context c, List<String> commands, BatchProgressListener progress) {
        String[] hosts = {"127.0.0.1"};
        for (String h : hosts) {
            if (h == null || h.isEmpty()) continue;
            if (!isPortOpen(h, 5555, 600)) continue;
            try {
                runShellCommandsOnHost(c, h, 5555, commands, progress);
                portReachable = true;
                setPaired(c, true);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static void runShellCommand(Context c, String host, int port, String shellCmd) throws Exception {
        runShellCommandOutput(c, host, port, shellCmd);
    }

    private static String runShellCommandOutput(Context c, String host, int port, String shellCmd) throws Exception {
        List<String> res = runShellCommandsOnHost(c, host, port, java.util.Collections.singletonList(shellCmd), null);
        return res.isEmpty() ? "" : res.get(0);
    }

    private static List<String> runShellCommandsOnHost(
            Context c, String host, int port, List<String> commands, BatchProgressListener progress) throws Exception {
        if (!"127.0.0.1".equals(host)) throw new Exception("LOOPBACK_ONLY");
        inFlight.incrementAndGet();
        try {
            return runShellCommandsLoggedIn(c, host, port, commands, progress);
        } finally {
            inFlight.decrementAndGet();
            if (weUsedAdb(c.getApplicationContext())) markAdbUsed(c);  // the 3-minute timer starts when work ends
        }
    }

    private static List<String> runShellCommandsLoggedIn(
            Context c, String host, int port, List<String> commands, BatchProgressListener progress) throws Exception {
        KeyPair kp = getOrCreateKeyPair(c);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(35000); // Allow up to 35s for user to tap "Allow USB debugging" on TV
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // 1. Send CNXN
            byte[] banner = "host::\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(out, A_CNXN, A_VERSION, MAX_PAYLOAD, banner);

            boolean sentSignature = false;
            while (true) {
                Packet p = readPacket(in);
                if (p.command == A_CNXN) {
                    break;
                } else if (p.command == A_AUTH) {
                    if (p.arg0 == ADB_AUTH_TOKEN) {
                        if (!sentSignature) {
                            sentSignature = true;
                            byte[] sig = signToken((RSAPrivateKey) kp.getPrivate(), p.data);
                            sendPacket(out, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
                        } else {
                            byte[] pubKeyPayload = encodeAndroidRsaPublicKey((RSAPublicKey) kp.getPublic());
                            sendPacket(out, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKeyPayload);
                        }
                    } else {
                        throw new Exception("Unexpected AUTH arg0: " + p.arg0);
                    }
                } else {
                    throw new Exception("Unexpected ADB response: " + p.command);
                }
            }

            // Logged in: from now on REYLauncher is responsible for switching ADB off again. Make sure it
            // can do that without ADB (and so without another "Allow debugging?" prompt).
            markAdbUsed(c);
            List<String> all = new java.util.ArrayList<>(commands);
            int offset = 0;
            if (!canWriteSecureSettings(c)) {
                all.add(0, "pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS 2>/dev/null");
                offset = 1;
            }
            socket.setSoTimeout(15000);
            List<String> outputs = new java.util.ArrayList<>(commands.size());
            int total = commands.size();
            for (int j = 0; j < all.size(); j++) {
                int i = j - offset;
                String shellCmd = all.get(j);
                int localId = j + 1;
                byte[] openData = ("shell:" + shellCmd + "\0").getBytes(StandardCharsets.UTF_8);
                sendPacket(out, A_OPEN, localId, 0, openData);

                StringBuilder sb = new StringBuilder();
                while (true) {
                    Packet p = readPacket(in);
                    if (p.command == A_WRTE) {
                        sendPacket(out, A_OKAY, localId, p.arg0, null);
                        if (p.data != null && p.data.length > 0) {
                            sb.append(new String(p.data, StandardCharsets.UTF_8));
                        }
                    } else if (p.command == A_CLSE) {
                        sendPacket(out, A_CLSE, localId, p.arg0, null);
                        break;
                    }
                }
                markAdbUsed(c);
                if (i < 0) continue;  // the permission grant above, not one of the caller's commands
                String outStr = sb.toString();
                outputs.add(outStr);
                if (progress != null) {
                    progress.onStep(i + 1, total, shellCmd, outStr);
                }
            }
            return outputs;
        }
    }

    private static final class Packet {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static void sendPacket(OutputStream out, int command, int arg0, int arg1, byte[] data) throws Exception {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) {
            for (byte b : data) {
                sum += (b & 0xFF);
            }
        }
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(command);
        hdr.putInt(arg0);
        hdr.putInt(arg1);
        hdr.putInt(len);
        hdr.putInt(sum);
        hdr.putInt(~command);
        out.write(hdr.array());
        if (len > 0) {
            out.write(data);
        }
        out.flush();
    }

    private static Packet readPacket(DataInputStream in) throws Exception {
        byte[] hdrBytes = new byte[24];
        in.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = hdr.getInt();
        p.arg0 = hdr.getInt();
        p.arg1 = hdr.getInt();
        int len = hdr.getInt();
        hdr.getInt(); // checksum
        hdr.getInt(); // magic
        if (len > 0) {
            p.data = new byte[len];
            in.readFully(p.data);
        } else {
            p.data = new byte[0];
        }
        return p;
    }

    private static byte[] signToken(RSAPrivateKey privKey, byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privKey);
        return cipher.doFinal(digestInfo);
    }

    /**
     * Encodes an RSAPublicKey into Android's 524-byte RSAPublicKey struct (Base64 + " tvoslauncher@tv\0").
     */
    private static byte[] encodeAndroidRsaPublicKey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32);
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(64); // 2048 bits / 32 bits
        buf.putInt((int) n0inv.longValue());
        buf.put(toLittleEndian256(n));
        buf.put(toLittleEndian256(rr));
        buf.putInt(pub.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        return (b64 + " tvoslauncher@tv\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] toLittleEndian256(BigInteger val) {
        byte[] be = val.toByteArray();
        byte[] le = new byte[256];
        int srcEnd = be.length;
        int copyLen = Math.min(256, srcEnd);
        for (int i = 0; i < copyLen; i++) {
            le[i] = be[srcEnd - 1 - i];
        }
        return le;
    }

    private static volatile KeyPair cachedKeyPair;

    private static KeyPair getOrCreateKeyPair(Context c) throws Exception {
        if (cachedKeyPair != null) return cachedKeyPair;
        File privFile = new File(c.getFilesDir(), "adb_rsa.priv");
        File pubFile = new File(c.getFilesDir(), "adb_rsa.pub");
        if (privFile.exists() && pubFile.exists()) {
            try {
                byte[] privBytes = readAll(privFile);
                byte[] pubBytes = readAll(pubFile);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                cachedKeyPair = new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(pubBytes)),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes)));
                return cachedKeyPair;
            } catch (Exception ignored) {
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        try (FileOutputStream fos = new FileOutputStream(privFile)) {
            fos.write(kp.getPrivate().getEncoded());
        }
        try (FileOutputStream fos = new FileOutputStream(pubFile)) {
            fos.write(kp.getPublic().getEncoded());
        }
        cachedKeyPair = kp;
        return kp;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = fis.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
        }
        return b;
    }
}
