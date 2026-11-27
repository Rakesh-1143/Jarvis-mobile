package com.growthos.jarvis;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Builds the Android intent that shows search results inside an app. Only standard, public intents. */
final class Searches {
    static final String YOUTUBE = "com.google.android.youtube", PLAY = "com.android.vending",
            MAPS = "com.google.android.apps.maps", SPOTIFY = "com.spotify.music", CHROME = "com.android.chrome",
            GOOGLE = "com.google.android.googlequicksearchbox";

    /** pkg null = search the web. Returns null when the app has no search Jarvis can open. */
    static Intent intent(Context c, String pkg, String q) {
        String e = Uri.encode(q);
        if (pkg == null || GOOGLE.equals(pkg)) {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q);
            if (installed(c, GOOGLE)) i.setPackage(GOOGLE);
            return resolves(c, i) ? i : view("https://www.google.com/search?q=" + e, null);
        }
        switch (pkg) {
            case YOUTUBE: {
                // YouTube opens its search results page for this link.
                Intent i = view("https://www.youtube.com/results?search_query=" + e, YOUTUBE);
                return resolves(c, i) ? i : new Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra(SearchManager.QUERY, q);
            }
            case PLAY: return view("market://search?q=" + e + "&c=apps", PLAY);
            case MAPS: return view("geo:0,0?q=" + e, MAPS);
            case SPOTIFY: return view("spotify:search:" + e, SPOTIFY);
            case CHROME: return view("https://www.google.com/search?q=" + e, CHROME);
            default: {
                // Many apps accept the standard Android search request.
                Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q);
                return resolves(c, i) ? i : null;
            }
        }
    }

    private static Intent view(String uri, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (pkg != null) i.setPackage(pkg);
        return i;
    }

    private static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception ex) { return false; }
    }

    private static boolean resolves(Context c, Intent i) {
        try { return c.getPackageManager().resolveActivity(i, 0) != null; } catch (Exception ex) { return false; }
    }
}
