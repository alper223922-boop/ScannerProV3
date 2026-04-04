package com.scanner.pro;

import java.util.ArrayList;
import java.util.List;

public class PatternDetector {

    public static class Candle {
        public double open, high, low, close;
        public Candle(double o, double h, double l, double c) {
            open = o; high = h; low = l; close = c;
        }
        public double body() { return Math.abs(close - open); }
        public double upperWick() { return high - Math.max(open, close); }
        public double lowerWick() { return Math.min(open, close) - low; }
        public double range() { return high - low; }
        public boolean isBull() { return close > open; }
        public boolean isBear() { return close < open; }
    }

    public static class Pattern {
        public String name;
        public String direction; // "bullish", "bearish", "neutral"
        public double tp1, tp2, stopLoss;

        public Pattern(String name, String direction) {
            this.name = name;
            this.direction = direction;
        }
    }

    public static List<Pattern> detect(Candle[] candles, double atrVal) {
        List<Pattern> found = new ArrayList<>();
        int n = candles.length;
        if (n < 5) return found;

        Candle c0 = candles[n-5], c1 = candles[n-4], c2 = candles[n-3],
               c3 = candles[n-2], c4 = candles[n-1];

        // Hammer
        if (c4.lowerWick() > c4.body() * 2 && c4.upperWick() < c4.body() * 0.5 && c4.range() > 0)
            found.add(make("Hammer", "bullish", c4, atrVal));

        // Shooting Star
        if (c4.upperWick() > c4.body() * 2 && c4.lowerWick() < c4.body() * 0.5 && c4.range() > 0)
            found.add(make("Shooting Star", "bearish", c4, atrVal));

        // Doji
        if (c4.range() > 0 && c4.body() < c4.range() * 0.1)
            found.add(make("Doji", "neutral", c4, atrVal));

        // Dragonfly Doji
        if (c4.range() > 0 && c4.body() < c4.range() * 0.05 && c4.lowerWick() > c4.range() * 0.7)
            found.add(make("Dragonfly Doji", "bullish", c4, atrVal));

        // Gravestone Doji
        if (c4.range() > 0 && c4.body() < c4.range() * 0.05 && c4.upperWick() > c4.range() * 0.7)
            found.add(make("Gravestone Doji", "bearish", c4, atrVal));

        // Bullish Engulfing
        if (c3.isBear() && c4.isBull() && c4.open < c3.close && c4.close > c3.open)
            found.add(make("Bullish Engulfing", "bullish", c4, atrVal));

        // Bearish Engulfing
        if (c3.isBull() && c4.isBear() && c4.open > c3.close && c4.close < c3.open)
            found.add(make("Bearish Engulfing", "bearish", c4, atrVal));

        // Bullish Harami
        if (c3.isBear() && c4.isBull() && c4.open > c3.close && c4.close < c3.open
                && c4.body() < c3.body() * 0.5)
            found.add(make("Bullish Harami", "bullish", c4, atrVal));

        // Bearish Harami
        if (c3.isBull() && c4.isBear() && c4.open < c3.close && c4.close > c3.open
                && c4.body() < c3.body() * 0.5)
            found.add(make("Bearish Harami", "bearish", c4, atrVal));

        // Morning Star
        if (c2.isBear() && c3.body() < c3.range() * 0.3 && c4.isBull()
                && c4.close > (c2.open + c2.close) / 2)
            found.add(make("Morning Star", "bullish", c4, atrVal));

        // Evening Star
        if (c2.isBull() && c3.body() < c3.range() * 0.3 && c4.isBear()
                && c4.close < (c2.open + c2.close) / 2)
            found.add(make("Evening Star", "bearish", c4, atrVal));

        // Three White Soldiers
        if (c2.isBull() && c3.isBull() && c4.isBull()
                && c3.open > c2.open && c4.open > c3.open
                && c3.close > c2.close && c4.close > c3.close)
            found.add(make("Three White Soldiers", "bullish", c4, atrVal));

        // Three Black Crows
        if (c2.isBear() && c3.isBear() && c4.isBear()
                && c3.open < c2.open && c4.open < c3.open
                && c3.close < c2.close && c4.close < c3.close)
            found.add(make("Three Black Crows", "bearish", c4, atrVal));

        // Bull Marubozu
        if (c4.isBull() && c4.body() > 0
                && c4.upperWick() < c4.body() * 0.05
                && c4.lowerWick() < c4.body() * 0.05)
            found.add(make("Bull Marubozu", "bullish", c4, atrVal));

        // Bear Marubozu
        if (c4.isBear() && c4.body() > 0
                && c4.upperWick() < c4.body() * 0.05
                && c4.lowerWick() < c4.body() * 0.05)
            found.add(make("Bear Marubozu", "bearish", c4, atrVal));

        // Piercing Line
        if (c3.isBear() && c4.isBull()
                && c4.open < c3.low
                && c4.close > (c3.open + c3.close) / 2
                && c4.close < c3.open)
            found.add(make("Piercing Line", "bullish", c4, atrVal));

        // Dark Cloud Cover
        if (c3.isBull() && c4.isBear()
                && c4.open > c3.high
                && c4.close < (c3.open + c3.close) / 2
                && c4.close > c3.open)
            found.add(make("Dark Cloud Cover", "bearish", c4, atrVal));

        // Tweezer Bottom
        if (c3.isBear() && c4.isBull() && c4.range() > 0
                && Math.abs(c3.low - c4.low) < c4.range() * 0.02)
            found.add(make("Tweezer Bottom", "bullish", c4, atrVal));

        // Tweezer Top
        if (c3.isBull() && c4.isBear() && c4.range() > 0
                && Math.abs(c3.high - c4.high) < c4.range() * 0.02)
            found.add(make("Tweezer Top", "bearish", c4, atrVal));

        // Spinning Top
        if (c4.range() > 0
                && c4.body() < c4.range() * 0.3
                && c4.upperWick() > c4.body()
                && c4.lowerWick() > c4.body())
            found.add(make("Spinning Top", "neutral", c4, atrVal));

        return found;
    }

    private static Pattern make(String name, String dir, Candle c, double atrVal) {
        Pattern p = new Pattern(name, dir);
        int mult = dir.equals("bullish") ? 1 : -1;
        p.tp1 = c.close + mult * atrVal * 1.5;
        p.tp2 = c.close + mult * atrVal * 3.0;
        p.stopLoss = c.close - mult * atrVal * 0.8;
        return p;
    }
}
