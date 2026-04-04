package com.scanner.pro;

import java.util.Arrays;

public class MathUtils {

    public static double[] ema(double[] arr, int period) {
        double k = 2.0 / (period + 1);
        double[] out = new double[arr.length];
        Arrays.fill(out, Double.NaN);
        if (arr.length < period) return out;
        double sum = 0;
        for (int i = 0; i < period; i++) sum += arr[i];
        out[period - 1] = sum / period;
        for (int i = period; i < arr.length; i++)
            out[i] = arr[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    public static double[] sma(double[] arr, int period) {
        double[] out = new double[arr.length];
        Arrays.fill(out, Double.NaN);
        for (int i = period - 1; i < arr.length; i++) {
            double s = 0;
            for (int j = i - period + 1; j <= i; j++) s += arr[j];
            out[i] = s / period;
        }
        return out;
    }

    public static double[] dema(double[] arr, int period) {
        // First EMA pass
        double[] e1 = ema(arr, period);
        // Extract valid portion of e1 for second EMA pass
        int validStart = -1;
        int validCount = 0;
        for (int i = 0; i < e1.length; i++) {
            if (!Double.isNaN(e1[i])) {
                if (validStart < 0) validStart = i;
                validCount++;
            }
        }
        if (validCount < period) {
            double[] out = new double[arr.length];
            Arrays.fill(out, Double.NaN);
            return out;
        }
        // Build clean array from valid e1 values
        double[] e1Valid = new double[validCount];
        int vi = 0;
        for (int i = 0; i < e1.length; i++)
            if (!Double.isNaN(e1[i])) e1Valid[vi++] = e1[i];
        // Second EMA pass on clean array
        double[] e2Valid = ema(e1Valid, period);
        // Map back to original size
        double[] out = new double[arr.length];
        Arrays.fill(out, Double.NaN);
        vi = 0;
        for (int i = validStart; i < arr.length; i++) {
            if (!Double.isNaN(e2Valid[vi]))
                out[i] = 2 * e1[i] - e2Valid[vi];
            vi++;
        }
        return out;
    }

    public static double[] rsi(double[] closes, int period) {
        double[] out = new double[closes.length];
        Arrays.fill(out, Double.NaN);
        if (closes.length < period + 1) return out;
        double gains = 0, losses = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes[i] - closes[i - 1];
            if (d > 0) gains += d; else losses -= d;
        }
        double avgG = gains / period, avgL = losses / period;
        out[period] = 100 - 100 / (1 + avgG / (avgL == 0 ? 0.001 : avgL));
        for (int i = period + 1; i < closes.length; i++) {
            double d = closes[i] - closes[i - 1];
            avgG = (avgG * (period - 1) + Math.max(d, 0)) / period;
            avgL = (avgL * (period - 1) + Math.max(-d, 0)) / period;
            out[i] = 100 - 100 / (1 + avgG / (avgL == 0 ? 0.001 : avgL));
        }
        return out;
    }

    public static class MACD {
        public double[] line, signal, hist;
    }

    public static MACD macd(double[] closes) {
        double[] e12 = ema(closes, 12), e26 = ema(closes, 26);
        double[] line = new double[closes.length];
        Arrays.fill(line, Double.NaN);
        int count = 0;
        for (int i = 0; i < closes.length; i++)
            if (!Double.isNaN(e12[i]) && !Double.isNaN(e26[i])) { line[i] = e12[i] - e26[i]; count++; }
        double[] validLine = new double[count];
        int vi = 0;
        for (double v : line) if (!Double.isNaN(v)) validLine[vi++] = v;
        double[] sigSmooth = ema(validLine, 9);
        double[] signal = new double[closes.length];
        Arrays.fill(signal, Double.NaN);
        vi = 0;
        for (int i = 0; i < closes.length; i++)
            if (!Double.isNaN(line[i])) signal[i] = sigSmooth[vi++];
        double[] hist = new double[closes.length];
        Arrays.fill(hist, Double.NaN);
        for (int i = 0; i < closes.length; i++)
            if (!Double.isNaN(line[i]) && !Double.isNaN(signal[i])) hist[i] = line[i] - signal[i];
        MACD m = new MACD(); m.line = line; m.signal = signal; m.hist = hist;
        return m;
    }

    public static class BBands {
        public double[] upper, mid, lower;
    }

    public static BBands bbands(double[] closes, int period, double mult) {
        double[] mid = sma(closes, period);
        double[] upper = new double[closes.length], lower = new double[closes.length];
        Arrays.fill(upper, Double.NaN); Arrays.fill(lower, Double.NaN);
        for (int i = period - 1; i < closes.length; i++) {
            if (Double.isNaN(mid[i])) continue;
            double sum2 = 0;
            for (int j = i - period + 1; j <= i; j++) sum2 += (closes[j] - mid[i]) * (closes[j] - mid[i]);
            double std = Math.sqrt(sum2 / period);
            upper[i] = mid[i] + mult * std;
            lower[i] = mid[i] - mult * std;
        }
        BBands b = new BBands(); b.upper = upper; b.mid = mid; b.lower = lower;
        return b;
    }

    public static double[] atr(double[] highs, double[] lows, double[] closes, int period) {
        double[] tr = new double[closes.length];
        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < closes.length; i++) {
            double hl = highs[i] - lows[i];
            double hpc = Math.abs(highs[i] - closes[i - 1]);
            double lpc = Math.abs(lows[i] - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }
        return sma(tr, period);
    }

    public static class Stoch {
        public double[] k, d;
    }

    public static Stoch stoch(double[] highs, double[] lows, double[] closes, int kPeriod, int dPeriod) {
        double[] k = new double[closes.length];
        Arrays.fill(k, Double.NaN);
        for (int i = kPeriod - 1; i < closes.length; i++) {
            double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
            for (int j = i - kPeriod + 1; j <= i; j++) {
                if (highs[j] > hh) hh = highs[j];
                if (lows[j] < ll) ll = lows[j];
            }
            k[i] = hh == ll ? 50 : ((closes[i] - ll) / (hh - ll)) * 100;
        }
        int count = 0;
        for (double v : k) if (!Double.isNaN(v)) count++;
        double[] validK = new double[count];
        int vi = 0;
        for (double v : k) if (!Double.isNaN(v)) validK[vi++] = v;
        double[] dSmooth = sma(validK, dPeriod);
        double[] d = new double[closes.length];
        Arrays.fill(d, Double.NaN);
        vi = 0;
        for (int i = 0; i < closes.length; i++)
            if (!Double.isNaN(k[i])) d[i] = dSmooth[vi++];
        Stoch s = new Stoch(); s.k = k; s.d = d;
        return s;
    }

    public static String detectCross(double[] fast, double[] slow) {
        int n = fast.length - 1;
        if (n < 1) return null;
        if (Double.isNaN(fast[n]) || Double.isNaN(slow[n]) ||
            Double.isNaN(fast[n-1]) || Double.isNaN(slow[n-1])) return null;
        if (fast[n-1] <= slow[n-1] && fast[n] > slow[n]) return "golden";
        if (fast[n-1] >= slow[n-1] && fast[n] < slow[n]) return "death";
        return null;
    }

    // ── ADX ──────────────────────────────────────────────────────────────────
    public static double adx(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period * 2) return Double.NaN;
        double[] plusDM = new double[n], minusDM = new double[n], trArr = new double[n];
        for (int i = 1; i < n; i++) {
            double upMove   = highs[i] - highs[i-1];
            double downMove = lows[i-1] - lows[i];
            plusDM[i]  = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
            double hl  = highs[i] - lows[i];
            double hpc = Math.abs(highs[i] - closes[i-1]);
            double lpc = Math.abs(lows[i] - closes[i-1]);
            trArr[i]   = Math.max(hl, Math.max(hpc, lpc));
        }
        double smoothTR = 0, smoothPDM = 0, smoothMDM = 0;
        for (int i = 1; i <= period; i++) {
            smoothTR  += trArr[i];
            smoothPDM += plusDM[i];
            smoothMDM += minusDM[i];
        }
        double adxSum = 0;
        for (int i = period + 1; i < n; i++) {
            smoothTR  = smoothTR  - smoothTR/period  + trArr[i];
            smoothPDM = smoothPDM - smoothPDM/period + plusDM[i];
            smoothMDM = smoothMDM - smoothMDM/period + minusDM[i];
            double pdi = smoothTR > 0 ? 100 * smoothPDM / smoothTR : 0;
            double mdi = smoothTR > 0 ? 100 * smoothMDM / smoothTR : 0;
            double dx  = (pdi + mdi) > 0 ? 100 * Math.abs(pdi - mdi) / (pdi + mdi) : 0;
            if (i >= period * 2 - 1)
                adxSum = (i == period * 2 - 1) ? dx : (adxSum * (period - 1) + dx) / period;
        }
        return adxSum;
    }

    // ── OBV Trend ────────────────────────────────────────────────────────────
    public static int obvTrend(double[] closes, double[] volumes, int lookback) {
        int n = closes.length;
        if (n < lookback + 1) return 0;
        double obv = 0;
        double[] obvArr = new double[n];
        for (int i = 1; i < n; i++) {
            if (closes[i] > closes[i-1])      obv += volumes[i];
            else if (closes[i] < closes[i-1]) obv -= volumes[i];
            obvArr[i] = obv;
        }
        double first = obvArr[n - lookback], last = obvArr[n - 1];
        if (last > first * 1.01) return 1;
        if (last < first * 0.99) return -1;
        return 0;
    }

    // ── ATR % ────────────────────────────────────────────────────────────────
    public static double atrPercent(double[] highs, double[] lows, double[] closes, int period) {
        double[] atrArr = atr(highs, lows, closes, period);
        double lastAtr   = atrArr[atrArr.length - 1];
        double lastClose = closes[closes.length - 1];
        return lastClose > 0 ? (lastAtr / lastClose) * 100 : 0;
    }

    // ── Pivot Points ─────────────────────────────────────────────────────────
    public static class Pivots {
        public double pp, r1, r2, s1, s2;
    }

    public static Pivots pivots(double[] highs, double[] lows, double[] closes) {
        int n = closes.length;
        double h = highs[n-2], l = lows[n-2], c = closes[n-2];
        Pivots p = new Pivots();
        p.pp = (h + l + c) / 3;
        p.r1 = 2 * p.pp - l;
        p.r2 = p.pp + (h - l);
        p.s1 = 2 * p.pp - h;
        p.s2 = p.pp - (h - l);
        return p;
    }
}
