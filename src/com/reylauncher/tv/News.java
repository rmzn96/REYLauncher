package com.reylauncher.tv;

/**
 * REYLauncher: the news feature (Indian newsrooms' RSS feeds) was removed, so nothing here touches
 * the network. Only the "time ago" label for shelf items remains.
 */
final class News {
    private News() {
    }

    /** "5 min ago", "2 h ago", "Yesterday"... for a publish time in milliseconds. */
    static String age(long published) {
        if (published <= 0) return "";
        long min = (System.currentTimeMillis() - published) / 60000;
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        return min < 48 * 60 ? "Yesterday" : (min / 1440) + " days ago";
    }
}
