package com.reylauncher.tv;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-shelf suggestions: what's popular in India on the user's streaming services, from TMDB
 * (themoviedb.org). Results and artwork are cached on disk; everything here runs off the UI thread.
 */
final class Suggestions {

    static final String PREFS = "suggestions";
    static final String K_ENABLED = "enabled", K_TOKEN = "tmdb_token", K_TYPES = "types",
            K_PROVIDERS = "providers", K_LANGS = "languages", K_ADVANCE = "advance_sec", K_VERSION = "version",
            K_SOURCE = "source", K_SHELF = "shelf";
    /** What the top shelf shows: recommendations, news, both (alternating) or nothing. */
    static final String SHELF_RECS = "recs", SHELF_NEWS = "news", SHELF_BOTH = "both", SHELF_NONE = "none";
    /**
     * Recommendation sources: JustWatch needs no key (unofficial API); TMDB needs a token; Stremio
     * is Cinemeta's public top lists, opened in the Stremio or Nuvio app.
     */
    static final String SRC_JUSTWATCH = "justwatch", SRC_TMDB = "tmdb", SRC_STREMIO = "stremio";
    static final long MAX_AGE_MS = 2L * 60 * 60 * 1000;  // recommendations rotate every 2 hours
    /** POOL titles are fetched per service; PER_SOURCE of them are picked at random each refresh. */
    static final int PER_SOURCE = 3, POOL = 20, MAX_ITEMS = 10;

    /** A streaming service: TMDB provider id, display name, Android TV package, JustWatch names. */
    static final class Provider {
        final int id;
        final String name, pkg, jwShort, jwTech;

        Provider(int id, String name, String pkg, String jwShort, String jwTech) {
            this.id = id;
            this.name = name;
            this.pkg = pkg;
            this.jwShort = jwShort;
            this.jwTech = jwTech;
        }
    }

    /** Streaming services in Turkey that REYLauncher knows by package (search falls back to these). */
    static final List<Provider> PROVIDERS = Arrays.asList(
            new Provider(8, "Netflix", "com.netflix.ninja", "nfx", "netflix"),
            new Provider(119, "Prime Video", "com.amazon.amazonvideo.livingroom", "prv", "amazonprimevideo"),
            new Provider(337, "Disney+", "com.disney.disneyplus", "dnp", "disneyplus"),
            new Provider(1899, "HBO Max", "com.wbd.stream", "mxx", "max"),
            new Provider(384, "HBO Max", "com.wbd.stream", "hbm", "hbomax"),
            new Provider(350, "Apple TV", "com.apple.atve.androidtv.appletv", "atp", "appletvplus"));

    /**
     * Apps that open Cinemeta titles by IMDb id. Not streaming services, so they never appear in
     * PROVIDERS; Stremio comes first because it is the one on the Play Store.
     */
    static final int STREMIO_ID = -1, NUVIO_ID = -2;
    static final Provider STREMIO = new Provider(STREMIO_ID, "Stremio", "com.stremio.one", "", ""),
            NUVIO = new Provider(NUVIO_ID, "Nuvio", "com.nuvio.tv", "", "");

    /** Original-language filters offered in settings (TMDB ISO 639-1 codes). */
    static final String[] LANG_CODES = {"hi", "en", "ta", "te", "ml", "kn", "bn", "mr"};
    static final String[] LANG_NAMES = {"Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi"};

    static final class Item {
        int id, providerId;
        String type, title, overview, backdrop, logo, year, genres;
        /** Exact title page on the service (JustWatch), opened directly in the app when it supports it. */
        String link = "";
        /** Continue Watching entries: the app that played it, its store key, and progress. */
        String pkg = "", key = "";
        float progress = -1f;
        int remainingMin = -1;
        double rating;
        /** News: when the story was published (ms), for "2 h ago". */
        long published;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("providerId", providerId).put("type", type)
                    .put("title", title).put("overview", overview).put("backdrop", backdrop)
                    .put("logo", logo == null ? "" : logo).put("year", year).put("genres", genres).put("rating", rating)
                    .put("link", link).put("pub", published);
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optInt("id");
            i.providerId = o.optInt("providerId");
            i.type = o.optString("type");
            i.title = o.optString("title");
            i.overview = o.optString("overview");
            i.backdrop = o.optString("backdrop");
            i.logo = o.optString("logo");
            if (i.logo.isEmpty()) i.logo = null;
            i.year = o.optString("year");
            i.genres = o.optString("genres");
            i.rating = o.optDouble("rating", 0);
            i.link = o.optString("link");
            i.published = o.optLong("pub");
            return i;
        }

        Provider provider() {
            for (Provider p : PROVIDERS) if (p.id == providerId) return p;
            return null;
        }

        /** The app that opens this title: its service, or for Stremio titles whichever player is installed. */
        Provider provider(Context c) {
            return providerId == STREMIO_ID ? stremioApp(c) : provider();
        }
    }

    // ------------------------------------------------------------------ settings

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * REYLauncher: recommendations and news are switched off for good, so nothing is fetched from
     * JustWatch/TMDB/Stremio or news sites for the top shelf. Continue Watching still shows there.
     */
    static String shelf(Context c) {
        return SHELF_NONE;
    }

    static void setShelf(Context c, String value) {
        Profiles.homePrefs(c).edit().putString(K_SHELF, value).apply();
        changed(c);
    }

    /** Recommendations and/or news for this profile, as two switches. */
    static void setShelf(Context c, boolean recs, boolean news) {
        setShelf(c, recs && news ? SHELF_BOTH : recs ? SHELF_RECS : news ? SHELF_NEWS : SHELF_NONE);
    }

    static boolean showRecs(Context c) {
        String s = shelf(c);
        return s.equals(SHELF_RECS) || s.equals(SHELF_BOTH);
    }

    static boolean showNews(Context c) {
        String s = shelf(c);
        return s.equals(SHELF_NEWS) || s.equals(SHELF_BOTH);
    }

    static boolean enabled(Context c) {
        return !shelf(c).equals(SHELF_NONE);
    }

    static String source(Context c) {
        return prefs(c).getString(K_SOURCE, SRC_JUSTWATCH);
    }

    /** Stremio when installed (or neither is), else Nuvio. */
    static Provider stremioApp(Context c) {
        return !installed(c, STREMIO.pkg) && installed(c, NUVIO.pkg) ? NUVIO : STREMIO;
    }

    static String sourceName(String s) {
        return s.equals(SRC_TMDB) ? "TMDB" : s.equals(SRC_STREMIO) ? "Stremio" : "JustWatch";
    }

    /** Whether the chosen source is usable right now (JustWatch and Stremio always; TMDB once a token is set). */
    static boolean ready(Context c) {
        return showNews(c) || !source(c).equals(SRC_TMDB) || hasToken(c);
    }

    static boolean hasToken(Context c) {
        return !prefs(c).getString(K_TOKEN, "").trim().isEmpty();
    }

    /** "both", "movie" or "tv". */
    static String types(Context c) {
        return prefs(c).getString(K_TYPES, "both");
    }

    static Set<String> providers(Context c) {
        Set<String> def = new HashSet<>();
        for (Provider p : PROVIDERS) def.add(String.valueOf(p.id));
        return new HashSet<>(prefs(c).getStringSet(K_PROVIDERS, def));
    }

    static Set<String> languages(Context c) {
        return new HashSet<>(prefs(c).getStringSet(K_LANGS, new HashSet<>()));
    }

    static int advanceSeconds(Context c) {
        return prefs(c).getInt(K_ADVANCE, 8);
    }

    /** Bumped on every settings change so the home screen knows to refetch. */
    static void changed(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(K_VERSION, p.getInt(K_VERSION, 0) + 1).apply();
        cacheFile(c).delete();
    }

    static int version(Context c) {
        return prefs(c).getInt(K_VERSION, 0);
    }

    // ------------------------------------------------------------------ data

    /** One cache per content mix, so profiles with different choices don't overwrite each other. */
    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "suggestions_" + shelf(c) + ".json");
    }

    /** Cached list, or null when missing/stale. */
    static List<Item> cached(Context c, boolean allowStale) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        long maxAge = MAX_AGE_MS;
        if (!allowStale && System.currentTimeMillis() - f.lastModified() > maxAge) return null;
        try {
            JSONArray a = new JSONArray(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * REYLauncher: recommendations and news were removed, so the top shelf is never filled from the
     * network (no JustWatch/TMDB/Stremio/news requests). Kept so callers compile; always empty.
     */
    static List<Item> fetch(Context c) {
        return new ArrayList<>();
    }

    static String logoFor(Context c, Item it) {
        return it.logo != null ? it.logo : "";
    }

    // ------------------------------------------------------------------ images

    /** TMDB image, cached on disk, decoded no larger than maxWidth (the box has little RAM). */
    static Bitmap image(Context c, String path, String size, int maxWidth) {
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("/")) {  // local artwork (Continue Watching)
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, o);
        }
        try {
            File dir = new File(c.getCacheDir(), "tmdb");
            dir.mkdirs();
            boolean abs = path.startsWith("http");
            File f = new File(dir, (abs ? "u" + Integer.toHexString(path.hashCode()) : size + "_" + path.replace("/", "")) + ".img");
            if (!f.exists()) {
                HttpURLConnection con = (HttpURLConnection) new URL(abs ? path : "https://image.tmdb.org/t/p/" + size + path).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(15000);
                try {
                    if (con.getResponseCode() != 200) return null;
                    byte[] data = readAll(con.getInputStream());
                    File tmp = new File(dir, f.getName() + ".part");
                    try (OutputStream os = new FileOutputStream(tmp)) {
                        os.write(data);
                    }
                    tmp.renameTo(f);
                } finally {
                    con.disconnect();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------------ deep links

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Opens the title in its streaming app as directly as the app allows: the app's own search
     * for the title (search intent, then its web-search link), falling back to launching the app,
     * and to its Play Store page when it isn't installed. TMDB has no per-service title ids, so
     * search is the most precise link available.
     */
    static void open(Context c, Item it) {
        Provider p = it.provider(c);
        if (p == null) return;
        if (!installed(c, p.pkg)) {
            start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + p.pkg)));
            return;
        }
        if (it.providerId == STREMIO_ID) {
            openStremio(c, it, p);
            return;
        }
        String q = it.title;
        List<Intent> tries = new ArrayList<>();
        if (it.link != null && !it.link.isEmpty()) tries.add(view(it.link, p.pkg));
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(p.pkg).putExtra(SearchManager.QUERY, q);
        tries.add(search);
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            enc = q;
        }
        switch (p.id) {
            case 8: tries.add(view("https://www.netflix.com/search?q=" + enc, p.pkg)); break;
            case 119: tries.add(view("https://www.primevideo.com/search/?phrase=" + enc, p.pkg)); break;
            case 2336: tries.add(view("https://www.hotstar.com/in/search?q=" + enc, p.pkg)); break;
            case 350: tries.add(view("https://tv.apple.com/search?term=" + enc, p.pkg)); break;
            case 232: tries.add(view("https://www.zee5.com/search?q=" + enc, p.pkg)); break;
            case 237: tries.add(view("https://www.sonyliv.com/search?searchTerm=" + enc, p.pkg)); break;
            default: break;
        }
        PackageManager pm = c.getPackageManager();
        for (Intent i : tries) {
            if (i.resolveActivity(pm) != null && start(c, i)) return;
        }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(p.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(p.pkg);
        if (launch != null) start(c, launch);
    }

    /**
     * Stremio titles open on their detail page: Stremio takes stremio:///detail/{type}/{id}/{videoId},
     * Nuvio takes nuvio://detail/{type}/{id} (its stremio:// handler only installs add-ons).
     */
    static void openStremio(Context c, Item it, Provider p) {
        String type = it.type.equals("tv") ? "series" : "movie";
        String url = p.id == NUVIO_ID ? "nuvio://detail/" + type + "/" + it.link
                : "stremio:///detail/" + type + "/" + it.link + (type.equals("movie") ? "/" + it.link : "");
        PackageManager pm = c.getPackageManager();
        Intent i = view(url, p.pkg);
        if (i.resolveActivity(pm) != null && start(c, i)) return;
        Intent launch = pm.getLeanbackLaunchIntentForPackage(p.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(p.pkg);
        if (launch != null) start(c, launch);
    }

    static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
    }

    static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
