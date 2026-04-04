package com.scanner.pro;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BinanceApi {

    private static final String BASE = "https://fapi.binance.com";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    public static class Kline {
        public long time;
        public double open, high, low, close, volume;
    }

    public static class Ticker {
        public String symbol;
        public double lastPrice, priceChangePercent, quoteVolume;
    }

    private static String get(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return resp.body().string();
        }
    }

    public static List<String> getSymbols() throws Exception {
        String json = get(BASE + "/fapi/v1/exchangeInfo");
        JSONObject obj = new JSONObject(json);
        JSONArray arr = obj.getJSONArray("symbols");
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            if ("TRADING".equals(s.optString("status")) &&
                "PERPETUAL".equals(s.optString("contractType")) &&
                "USDT".equals(s.optString("quoteAsset"))) {
                symbols.add(s.getString("symbol"));
            }
        }
        return symbols;
    }

    public static List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {
        String url = BASE + "/fapi/v1/klines?symbol=" + symbol +
                "&interval=" + interval + "&limit=" + limit;
        String json = get(url);
        JSONArray arr = new JSONArray(json);
        List<Kline> klines = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray c = arr.getJSONArray(i);
            Kline k = new Kline();
            k.time   = c.getLong(0);
            k.open   = c.getDouble(1);
            k.high   = c.getDouble(2);
            k.low    = c.getDouble(3);
            k.close  = c.getDouble(4);
            k.volume = c.getDouble(5);
            klines.add(k);
        }
        return klines;
    }

    public static List<Ticker> get24hrAll() throws Exception {
        String json = get(BASE + "/fapi/v1/ticker/24hr");
        JSONArray arr = new JSONArray(json);
        List<Ticker> tickers = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Ticker t = new Ticker();
            t.symbol             = o.optString("symbol");
            t.lastPrice          = o.optDouble("lastPrice", 0);
            t.priceChangePercent = o.optDouble("priceChangePercent", 0);
            t.quoteVolume        = o.optDouble("quoteVolume", 0);
            tickers.add(t);
        }
        return tickers;
    }
}
