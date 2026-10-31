package com.lumahome.launcher;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WeatherService {
    static final class ForecastDay {
        final String dayName;
        final float maxTemp, minTemp;
        final int weatherCode;
        final String conditionText;
        final String emoji;

        ForecastDay(String dayName, float maxTemp, float minTemp, int weatherCode, String conditionText, String emoji) {
            this.dayName = dayName;
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.weatherCode = weatherCode;
            this.conditionText = conditionText;
            this.emoji = emoji;
        }
    }

    static final class WeatherInfo {
        final String city;
        final float currentTemp;
        final int weatherCode;
        final boolean isDay;
        final float windSpeed;
        final float todayMax, todayMin;
        final String conditionText;
        final String emoji;
        final String unit; // "°C" or "°F"
        final long lastUpdated;
        final List<ForecastDay> forecast;

        WeatherInfo(String city, float currentTemp, int weatherCode, boolean isDay, float windSpeed,
                    float todayMax, float todayMin, String conditionText, String emoji, String unit,
                    long lastUpdated, List<ForecastDay> forecast) {
            this.city = city;
            this.currentTemp = currentTemp;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
            this.windSpeed = windSpeed;
            this.todayMax = todayMax;
            this.todayMin = todayMin;
            this.conditionText = conditionText;
            this.emoji = emoji;
            this.unit = unit;
            this.lastUpdated = lastUpdated;
            this.forecast = forecast != null ? forecast : new ArrayList<>();
        }

        String tempDisplay() {
            return Math.round(currentTemp) + unit;
        }

        String rangeDisplay() {
            return "High " + Math.round(todayMax) + "° · Low " + Math.round(todayMin) + "°";
        }
    }

    interface WeatherCallback {
        void onWeatherLoaded(WeatherInfo info);
        default void onWeatherError(String message) {}
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    static String codeToText(int code) {
        switch (code) {
            case 0: return "Clear Sky";
            case 1: return "Mainly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: return "Light Rain";
            case 63: return "Moderate Rain";
            case 65: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: return "Slight Snow";
            case 73: return "Moderate Snow";
            case 75: return "Heavy Snow";
            case 77: return "Snow Grains";
            case 80: return "Light Showers";
            case 81: return "Showers";
            case 82: return "Heavy Showers";
            case 85: case 86: return "Snow Showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunder & Hail";
            default: return "Partly Cloudy";
        }
    }

    static String codeToEmoji(int code, boolean isDay) {
        switch (code) {
            case 0: return isDay ? "☀️" : "🌙";
            case 1: return isDay ? "🌤️" : "🌙";
            case 2: return isDay ? "⛅" : "☁️";
            case 3: return "☁️";
            case 45: case 48: return "🌫️";
            case 51: case 53: case 55: return "🌦️";
            case 56: case 57: return "🌨️";
            case 61: case 63: case 65: return "🌧️";
            case 66: case 67: return "🌨️";
            case 71: case 73: case 75: case 77: return "❄️";
            case 80: case 81: case 82: return "🌧️";
            case 85: case 86: return "🌨️";
            case 95: case 96: case 99: return "⛈️";
            default: return isDay ? "🌤️" : "☁️";
        }
    }

    static WeatherInfo getCached(LauncherStore store) {
        String raw = store.value("weather_cached_data", "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(raw);
            String city = obj.optString("city", "Local");
            float cur = (float) obj.optDouble("current_temp", 20.0);
            int code = obj.optInt("weather_code", 0);
            boolean day = obj.optBoolean("is_day", true);
            float wind = (float) obj.optDouble("wind_speed", 0.0);
            float max = (float) obj.optDouble("today_max", cur);
            float min = (float) obj.optDouble("today_min", cur);
            String text = obj.optString("condition_text", codeToText(code));
            String emoji = obj.optString("emoji", codeToEmoji(code, day));
            String unit = obj.optString("unit", "°C");
            long updated = obj.optLong("updated", 0L);

            List<ForecastDay> list = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("forecast");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.getJSONObject(i);
                    list.add(new ForecastDay(
                            d.optString("day", ""),
                            (float) d.optDouble("max", 0.0),
                            (float) d.optDouble("min", 0.0),
                            d.optInt("code", 0),
                            d.optString("text", ""),
                            d.optString("emoji", "")
                    ));
                }
            }
            return new WeatherInfo(city, cur, code, day, wind, max, min, text, emoji, unit, updated, list);
        } catch (Exception ignored) {
            return null;
        }
    }

    static void saveCached(LauncherStore store, WeatherInfo info) {
        if (info == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("city", info.city);
            obj.put("current_temp", info.currentTemp);
            obj.put("weather_code", info.weatherCode);
            obj.put("is_day", info.isDay);
            obj.put("wind_speed", info.windSpeed);
            obj.put("today_max", info.todayMax);
            obj.put("today_min", info.todayMin);
            obj.put("condition_text", info.conditionText);
            obj.put("emoji", info.emoji);
            obj.put("unit", info.unit);
            obj.put("updated", info.lastUpdated);

            JSONArray arr = new JSONArray();
            for (ForecastDay d : info.forecast) {
                JSONObject dayObj = new JSONObject();
                dayObj.put("day", d.dayName);
                dayObj.put("max", d.maxTemp);
                dayObj.put("min", d.minTemp);
                dayObj.put("code", d.weatherCode);
                dayObj.put("text", d.conditionText);
                dayObj.put("emoji", d.emoji);
                arr.put(dayObj);
            }
            obj.put("forecast", arr);
            store.set("weather_cached_data", obj.toString());
        } catch (Exception ignored) {}
    }

    void fetch(LauncherStore store, boolean force, WeatherCallback callback) {
        WeatherInfo cached = getCached(store);
        long now = System.currentTimeMillis();
        if (!force && cached != null && (now - cached.lastUpdated < 20 * 60 * 1000L)) {
            if (callback != null) callback.onWeatherLoaded(cached);
            return;
        }
        if (cached != null && callback != null) {
            callback.onWeatherLoaded(cached);
        }

        worker.execute(() -> {
            try {
                String unitPref = store.value("temp_unit", "c");
                String unitSymbol = "f".equalsIgnoreCase(unitPref) ? "°F" : "°C";
                String customCity = store.value("weather_custom_city", "").trim();

                double lat = 0.0, lon = 0.0;
                String cityName = "";

                if (!customCity.isEmpty()) {
                    String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                            + URLEncoder.encode(customCity, "UTF-8") + "&count=1&language=en&format=json";
                    String geoJson = httpGet(geoUrl);
                    JSONObject geoObj = new JSONObject(geoJson);
                    JSONArray results = geoObj.optJSONArray("results");
                    if (results != null && results.length() > 0) {
                        JSONObject first = results.getJSONObject(0);
                        lat = first.optDouble("latitude");
                        lon = first.optDouble("longitude");
                        cityName = first.optString("name");
                        String country = first.optString("country", "");
                        if (!country.isEmpty()) cityName = cityName + ", " + country;
                    }
                }

                if (cityName.isEmpty()) {
                    try {
                        String ipJson = httpGet("https://freeipapi.com/api/json");
                        JSONObject ipObj = new JSONObject(ipJson);
                        lat = ipObj.optDouble("latitude");
                        lon = ipObj.optDouble("longitude");
                        cityName = ipObj.optString("cityName");
                    } catch (Exception e) {
                        try {
                            String ipJson = httpGet("https://ipapi.co/json/");
                            JSONObject ipObj = new JSONObject(ipJson);
                            lat = ipObj.optDouble("latitude");
                            lon = ipObj.optDouble("longitude");
                            cityName = ipObj.optString("city");
                        } catch (Exception ignored) {}
                    }
                }

                if (cityName.isEmpty()) {
                    cityName = "Local";
                }

                String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current_weather=true&daily=temperature_2m_max,temperature_2m_min,weathercode&timezone=auto"
                        + ("f".equalsIgnoreCase(unitPref) ? "&temperature_unit=fahrenheit" : "");
                String weatherJson = httpGet(weatherUrl);
                JSONObject wObj = new JSONObject(weatherJson);
                JSONObject curr = wObj.getJSONObject("current_weather");
                float curTemp = (float) curr.optDouble("temperature");
                int code = curr.optInt("weathercode");
                boolean isDay = curr.optInt("is_day", 1) == 1;
                float wind = (float) curr.optDouble("windspeed");

                JSONObject daily = wObj.optJSONObject("daily");
                float todayMax = curTemp, todayMin = curTemp;
                List<ForecastDay> forecast = new ArrayList<>();

                if (daily != null) {
                    JSONArray times = daily.optJSONArray("time");
                    JSONArray maxs = daily.optJSONArray("temperature_2m_max");
                    JSONArray mins = daily.optJSONArray("temperature_2m_min");
                    JSONArray codes = daily.optJSONArray("weathercode");

                    SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.getDefault());

                    if (times != null) {
                        int count = Math.min(5, times.length());
                        for (int i = 0; i < count; i++) {
                            String dateStr = times.optString(i);
                            float dMax = (float) (maxs != null ? maxs.optDouble(i) : curTemp);
                            float dMin = (float) (mins != null ? mins.optDouble(i) : curTemp);
                            int dCode = codes != null ? codes.optInt(i) : code;
                            if (i == 0) {
                                todayMax = dMax;
                                todayMin = dMin;
                            }
                            String dayLabel = "Today";
                            if (i == 1) {
                                dayLabel = "Tomorrow";
                            } else if (i > 1) {
                                try {
                                    Date parsed = parser.parse(dateStr);
                                    if (parsed != null) dayLabel = dayFmt.format(parsed);
                                } catch (Exception ignored) {}
                            }
                            forecast.add(new ForecastDay(dayLabel, dMax, dMin, dCode, codeToText(dCode), codeToEmoji(dCode, true)));
                        }
                    }
                }

                WeatherInfo info = new WeatherInfo(cityName, curTemp, code, isDay, wind,
                        todayMax, todayMin, codeToText(code), codeToEmoji(code, isDay), unitSymbol,
                        System.currentTimeMillis(), forecast);
                saveCached(store, info);

                if (!closed && callback != null) {
                    ui.post(() -> {
                        if (!closed) callback.onWeatherLoaded(info);
                    });
                }
            } catch (Exception e) {
                if (!closed && callback != null) {
                    final String msg = e.getMessage() != null ? e.getMessage() : "Failed to load weather";
                    ui.post(() -> {
                        if (!closed) callback.onWeatherError(msg);
                    });
                }
            }
        });
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "LumaHome/1.0");
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }
}
