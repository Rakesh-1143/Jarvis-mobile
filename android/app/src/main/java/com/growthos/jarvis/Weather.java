package com.growthos.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/** Current weather from Open-Meteo (free, no key, no account). Call off the main thread. */
final class Weather {
    private Weather() { }

    static String now(String city) {
        try {
            JSONObject g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + URLEncoder.encode(city, "UTF-8"));
            JSONArray r = g.optJSONArray("results");
            if (r == null || r.length() == 0) return "Boss, I couldn't find " + city + ".";
            JSONObject place = r.getJSONObject(0);
            double lat = place.getDouble("latitude"), lon = place.getDouble("longitude");
            JSONObject w = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1");
            JSONObject c = w.getJSONObject("current");
            JSONObject d = w.getJSONObject("daily");
            int rain = d.getJSONArray("precipitation_probability_max").optInt(0, -1);
            return "Boss, in " + place.getString("name") + " it's " + Math.round(c.getDouble("temperature_2m")) + " degrees and " + describe(c.getInt("weather_code"))
                    + ". Today's high is " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + " and low " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))
                    + (rain >= 0 ? ", with a " + rain + " percent chance of rain." : ".");
        } catch (Exception e) { return "Boss, I couldn't get the weather right now."; }
    }

    /** WMO weather codes in plain words. */
    static String describe(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "drizzling";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "raining";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowing";
        if (code >= 95) return "stormy";
        return "mixed";
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(10000);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        }
    }
}
