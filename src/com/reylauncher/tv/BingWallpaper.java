package com.reylauncher.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bing's photo of the day, used as the "Bing" Night Scenes scene and as the Classic background.
 *
 * Network use (only while one of the two is switched on, at most once a day):
 *   1. GET https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=tr-TR
 *   2. GET https://www.bing.com/th?id=... (the image path from step 1, always on www.bing.com)
 * No account, key, cookie or information about this TV is sent; Bing sees only the requests.
 */
final class BingWallpaper {
    private static final String PREFS = "bing_wallpaper";
    private static final String K_DAY = "day", K_CAPTION = "caption";
    private static final String HOST = "https://www.bing.com";
    private static final String API = HOST + "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=tr-TR";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static volatile boolean running;

    interface Done {
        void onDone(boolean ok);
    }

    private BingWallpaper() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** The photo's description and credit from Bing (e.g. "… (© photographer)"), or "". */
    static String caption(Context c) {
        return prefs(c).getString(K_CAPTION, "");
    }

    /** Whether Night Scenes shows the Bing scene or Classic uses the Bing background. */
    static boolean inUse(Context c) {
        boolean scene = Scenes.getEnabledSceneIdsRaw(c).contains(Scenes.CUSTOM_ID);
        boolean classic = MainActivity.CUSTOM_WALL_KEY.equals(
                Profiles.homePrefs(c).getString("wallpaper", ""));
        return scene || classic;
    }

    /** Fetches today's photo if it is in use and not fetched yet today. {@code done} runs on a background thread. */
    static void refreshIfStale(Context c, Done done) {
        if (c == null || !inUse(c)) return;
        boolean have = CustomMedia.hasCustomSceneWallpaper(c, 1);
        if (have && today().equals(prefs(c).getString(K_DAY, ""))) return;
        refreshNow(c, done);
    }

    /** Fetches today's photo now. {@code done} runs on a background thread (may be null). */
    static void refreshNow(Context c, Done done) {
        if (c == null || running) {
            if (done != null) done.onDone(false);
            return;
        }
        final Context appCtx = c.getApplicationContext();
        running = true;
        new Thread(() -> {
            boolean ok = false;
            try {
                JSONObject json = new JSONObject(new String(get(API, 256 * 1024), StandardCharsets.UTF_8));
                JSONArray images = json.getJSONArray("images");
                JSONObject img = images.getJSONObject(0);
                String path = img.optString("url", "");
                // Only ever download from www.bing.com itself.
                if (!path.startsWith("/") || path.startsWith("//")) throw new Exception("bad image path");
                byte[] data = get(HOST + path, MAX_BYTES);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp == null) throw new Exception("not an image");
                boolean scene = CustomMedia.setCustomSceneWallpaper(appCtx, 1, bmp);
                boolean classic = CustomMedia.setCustomClassicWallpaper(appCtx, bmp);
                ok = scene && classic;
                if (ok) {
                    prefs(appCtx).edit()
                            .putString(K_DAY, today())
                            .putString(K_CAPTION, img.optString("copyright", ""))
                            .apply();
                }
            } catch (Throwable t) {
                android.util.Log.w("REYLauncher", "Bing photo: " + t);
            } finally {
                running = false;
            }
            if (done != null) done.onDone(ok);
        }, "bing-wallpaper").start();
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "REYLauncher");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new Exception("too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
