package com.scanner.pro;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.widget.LinearLayout;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.google.android.material.chip.*;
import java.util.*;
import java.util.concurrent.*;

public class MultiFragment extends Fragment {

    private static final String[] TF_LABELS = {"5m","15m","30m","1h","2h","4h","1d"};

    private ChipGroup cgTimeframe;
    private RadioGroup rgDir;
    private Button btnScan;
    private ProgressBar progressBar;
    private TextView tvProgress, tvResultCount;
    private RecyclerView recyclerView;

    private final List<MultiResult> results = new ArrayList<>();
    private MultiAdapter adapter;
    private volatile boolean scanning = false;
    private ExecutorService executor;

    public static class Signal {
        String name, value, bias;
        Signal(String n, String v, String b) { name=n; value=v; bias=b; }
    }

    public static class MultiResult {
        String symbol, direction;
        int score;
        List<Signal> signals = new ArrayList<>();
        // Ek onaylar
        double adx, atrPct, price, changePercent;
        int obvTrend; // +1, -1, 0
        MathUtils.Pivots pivots;
        String emaTrend; // "BULL" / "BEAR"
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_multi, container, false);
        cgTimeframe   = v.findViewById(R.id.cgTimeframe);
        rgDir         = v.findViewById(R.id.rgDir);
        btnScan       = v.findViewById(R.id.btnScan);
        progressBar   = v.findViewById(R.id.progressBar);
        tvProgress    = v.findViewById(R.id.tvProgress);
        tvResultCount = v.findViewById(R.id.tvResultCount);
        recyclerView  = v.findViewById(R.id.recyclerView);

        for (String tf : TF_LABELS) {
            Chip chip = new Chip(requireContext());
            chip.setText(tf); chip.setTag(tf); chip.setCheckable(true);
            chip.setChecked(tf.equals("1h"));
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#080E1A")));
            chip.setTextColor(android.graphics.Color.parseColor("#4E7399"));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E3358")));
            chip.setChipStrokeWidth(1f); chip.setTextSize(10f);
            cgTimeframe.addView(chip);
        }

        adapter = new MultiAdapter(results);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        btnScan.setOnClickListener(x -> { if (scanning) stopScan(); else startScan(); });
        return v;
    }

    private String getSelectedTf() {
        for (int i = 0; i < cgTimeframe.getChildCount(); i++) {
            Chip c = (Chip) cgTimeframe.getChildAt(i);
            if (c.isChecked()) return (String) c.getTag();
        }
        return "1h";
    }

    private String getDirFilter() {
        int id = rgDir.getCheckedRadioButtonId();
        if (id == R.id.rbLong)  return "LONG";
        if (id == R.id.rbShort) return "SHORT";
        return "all";
    }

    private void startScan() {
        scanning = true;
        results.clear(); adapter.notifyDataSetChanged();
        btnScan.setText("DURDUR");
        btnScan.setBackgroundColor(Color.parseColor("#3D1A00"));
        btnScan.setTextColor(Color.parseColor("#F0B90B"));
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvResultCount.setText("0 SONUC");

        String tf = getSelectedTf();
        String dirFilter = getDirFilter();
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runScan(tf, dirFilter));
    }

    private void stopScan() {
        scanning = false;
        if (executor != null) executor.shutdownNow();
        resetUI();
    }

    private void runScan(String tf, String dirFilter) {
        try {
            List<String> symbols = BinanceApi.getSymbols();
            List<BinanceApi.Ticker> tickers = BinanceApi.get24hrAll();
            Map<String, BinanceApi.Ticker> tickerMap = new HashMap<>();
            for (BinanceApi.Ticker t : tickers) tickerMap.put(t.symbol, t);
            int total = symbols.size();

            for (int i = 0; i < symbols.size() && scanning; i++) {
                String sym = symbols.get(i);
                final int prog = i + 1;
                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(prog + " / " + total);
                    progressBar.setProgress((int)(prog * 100f / total));
                });

                try {
                    List<BinanceApi.Kline> klines = BinanceApi.getKlines(sym, tf, 250);
                    int sz = klines.size();
                    double[] closes  = new double[sz];
                    double[] highs   = new double[sz];
                    double[] lows    = new double[sz];
                    double[] volumes = new double[sz];
                    for (int k = 0; k < sz; k++) {
                        closes[k]  = klines.get(k).close;
                        highs[k]   = klines.get(k).high;
                        lows[k]    = klines.get(k).low;
                        volumes[k] = klines.get(k).volume;
                    }

                    MultiResult res = calcSignals(sym, closes, highs, lows, volumes);
                    if (res.direction.equals("NEUTRAL")) continue;
                    if (!dirFilter.equals("all") && !res.direction.equals(dirFilter)) continue;

                    BinanceApi.Ticker tk = tickerMap.get(sym);
                    res.price = tk != null ? tk.lastPrice : 0;
                    res.changePercent = tk != null ? tk.priceChangePercent : 0;

                    requireActivity().runOnUiThread(() -> {
                        int insertPos = 0;
                        while (insertPos < results.size() &&
                               Math.abs(results.get(insertPos).score) >= Math.abs(res.score))
                            insertPos++;
                        results.add(insertPos, res);
                        adapter.notifyItemInserted(insertPos);
                        tvResultCount.setText(results.size() + " SONUC");
                    });
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
        requireActivity().runOnUiThread(this::resetUI);
    }

    private MultiResult calcSignals(String sym, double[] closes, double[] highs,
                                    double[] lows, double[] volumes) {
        MultiResult res = new MultiResult();
        res.symbol = sym;
        int n = closes.length - 1;
        int score = 0;

        // ── RSI ──────────────────────────────────────────────────────────────
        double[] rsiArr = MathUtils.rsi(closes, 14);
        double rsiVal = rsiArr[n];
        if (!Double.isNaN(rsiVal)) {
            if (rsiVal < 35)      { res.signals.add(new Signal("RSI", String.format("%.1f", rsiVal), "bull")); score += 1; }
            else if (rsiVal > 65) { res.signals.add(new Signal("RSI", String.format("%.1f", rsiVal), "bear")); score -= 1; }
            else                  { res.signals.add(new Signal("RSI", String.format("%.1f", rsiVal), "neutral")); }
        }

        // ── MACD ─────────────────────────────────────────────────────────────
        MathUtils.MACD macdData = MathUtils.macd(closes);
        double ml = macdData.line[n], ms = macdData.signal[n];
        double ml1 = n>0 ? macdData.line[n-1] : Double.NaN;
        double ms1 = n>0 ? macdData.signal[n-1] : Double.NaN;
        if (!Double.isNaN(ml) && !Double.isNaN(ms) && !Double.isNaN(ml1) && !Double.isNaN(ms1)) {
            if (ml1 < ms1 && ml > ms)      { res.signals.add(new Signal("MACD", "Cross up", "bull")); score += 2; }
            else if (ml1 > ms1 && ml < ms) { res.signals.add(new Signal("MACD", "Cross dn", "bear")); score -= 2; }
            else { res.signals.add(new Signal("MACD", ml > ms ? "Bull" : "Bear", ml > ms ? "bull" : "bear")); score += ml > ms ? 1 : -1; }
        }

        // ── Bollinger Bands ───────────────────────────────────────────────────
        MathUtils.BBands bb = MathUtils.bbands(closes, 20, 2.0);
        double bbU = bb.upper[n], bbL = bb.lower[n], price = closes[n];
        if (!Double.isNaN(bbU) && !Double.isNaN(bbL)) {
            if (price < bbL)      { res.signals.add(new Signal("BB", "Asagi", "bull")); score += 1; }
            else if (price > bbU) { res.signals.add(new Signal("BB", "Yukari", "bear")); score -= 1; }
            else                  { res.signals.add(new Signal("BB", "Icinde", "neutral")); }
        }

        // ── Stochastic ───────────────────────────────────────────────────────
        MathUtils.Stoch st = MathUtils.stoch(highs, lows, closes, 14, 3);
        double sk = st.k[n], sd = st.d[n];
        if (!Double.isNaN(sk) && !Double.isNaN(sd)) {
            if (sk < 20 && sk > sd)      { res.signals.add(new Signal("Stoch", String.format("%.0f", sk), "bull")); score += 1; }
            else if (sk > 80 && sk < sd) { res.signals.add(new Signal("Stoch", String.format("%.0f", sk), "bear")); score -= 1; }
            else                         { res.signals.add(new Signal("Stoch", String.format("%.0f", sk), "neutral")); }
        }

        // ── EMA Trend (50 vs 200) ─────────────────────────────────────────────
        if (closes.length >= 200) {
            double[] e50  = MathUtils.ema(closes, 50);
            double[] e200 = MathUtils.ema(closes, 200);
            if (!Double.isNaN(e50[n]) && !Double.isNaN(e200[n])) {
                res.emaTrend = e50[n] > e200[n] ? "BULL" : "BEAR";
                if (e50[n] > e200[n]) { res.signals.add(new Signal("EMA", "50>200", "bull")); score += 1; }
                else                  { res.signals.add(new Signal("EMA", "50<200", "bear")); score -= 1; }
            }
        }

        // ── ADX (trend gucu) ─────────────────────────────────────────────────
        res.adx = MathUtils.adx(highs, lows, closes, 14);
        if (!Double.isNaN(res.adx)) {
            String adxStr = String.format("%.0f", res.adx);
            if (res.adx > 25)      res.signals.add(new Signal("ADX", adxStr + " Guclu", "bull"));
            else if (res.adx > 20) res.signals.add(new Signal("ADX", adxStr + " Orta", "neutral"));
            else                   res.signals.add(new Signal("ADX", adxStr + " Zayif", "neutral"));
        }

        // ── ATR % (volatilite) ───────────────────────────────────────────────
        res.atrPct = MathUtils.atrPercent(highs, lows, closes, 14);
        if (res.atrPct > 0) {
            String atrStr = String.format("%.2f%%", res.atrPct);
            String bias = res.atrPct > 3 ? "bear" : res.atrPct > 1.5 ? "neutral" : "bull";
            res.signals.add(new Signal("ATR", atrStr, bias));
        }

        // ── OBV Trend ────────────────────────────────────────────────────────
        res.obvTrend = MathUtils.obvTrend(closes, volumes, 20);
        if (res.obvTrend == 1)       { res.signals.add(new Signal("OBV", "Yukseliyor", "bull")); score += 1; }
        else if (res.obvTrend == -1) { res.signals.add(new Signal("OBV", "Dusuyor", "bear")); score -= 1; }
        else                         { res.signals.add(new Signal("OBV", "Yatay", "neutral")); }

        // ── Pivot Points ─────────────────────────────────────────────────────
        if (closes.length >= 3) {
            res.pivots = MathUtils.pivots(highs, lows, closes);
        }

        res.score = score;
        res.direction = score >= 2 ? "LONG" : score <= -2 ? "SHORT" : "NEUTRAL";
        return res;
    }

    private void resetUI() {
        scanning = false;
        btnScan.setText("TARA");
        btnScan.setBackgroundColor(Color.parseColor("#F0B90B"));
        btnScan.setTextColor(Color.BLACK);
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    // ── Adapter ──────────────────────────────────────────────────────────────
    static class MultiAdapter extends RecyclerView.Adapter<MultiAdapter.VH> {
        private final List<MultiResult> data;
        MultiAdapter(List<MultiResult> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_multi_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MultiResult r = data.get(pos);
            h.tvSymbol.setText(r.symbol);
            h.tvPrice.setText(r.price < 1
                    ? String.format("$%.5f", r.price)
                    : String.format("$%.2f", r.price));

            boolean isLong = r.direction.equals("LONG");
            h.tvDirection.setText((isLong ? "LONG" : "SHORT") +
                    " (" + (r.score > 0 ? "+" : "") + r.score + ")");
            h.tvDirection.setTextColor(Color.parseColor(isLong ? "#00E676" : "#FF4060"));

            // Ana sinyaller
            h.cgSignals.removeAllViews();
            for (Signal s : r.signals) {
                Chip chip = new Chip(h.itemView.getContext());
                chip.setText(s.name + ": " + s.value);
                int bg = s.bias.equals("bull") ? Color.parseColor("#001F0E")
                       : s.bias.equals("bear") ? Color.parseColor("#1F0008")
                       : Color.parseColor("#080E1A");
                int tc = s.bias.equals("bull") ? Color.parseColor("#00E676")
                       : s.bias.equals("bear") ? Color.parseColor("#FF4060")
                       : Color.parseColor("#4E7399");
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(bg));
                chip.setTextColor(tc);
                chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(bg));
                chip.setChipStrokeWidth(1f); chip.setTextSize(9f); chip.setClickable(false);
                h.cgSignals.addView(chip);
            }

            // Pivot seviyeleri
            if (r.pivots != null) {
                h.llPivots.setVisibility(View.VISIBLE);
                h.tvPP.setText(String.format("PP %.2f", r.pivots.pp));
                h.tvR1.setText(String.format("R1 %.2f", r.pivots.r1));
                h.tvS1.setText(String.format("S1 %.2f", r.pivots.s1));
            } else {
                if (h.llPivots != null) h.llPivots.setVisibility(View.GONE);
            }

            // 24h degisim
            h.tvChange.setText(String.format("%+.2f%%", r.changePercent));
            h.tvChange.setTextColor(r.changePercent >= 0
                    ? Color.parseColor("#00E676") : Color.parseColor("#FF4060"));
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSymbol, tvPrice, tvDirection, tvChange, tvVolume, tvPivots, tvPP, tvR1, tvS1;
            android.widget.LinearLayout llPivots;
            ChipGroup cgSignals;
            VH(View v) {
                super(v);
                tvSymbol    = v.findViewById(R.id.tvSymbol);
                tvPrice     = v.findViewById(R.id.tvPrice);
                tvDirection = v.findViewById(R.id.tvDirection);
                tvChange    = v.findViewById(R.id.tvChange);
                tvVolume    = v.findViewById(R.id.tvVolume);
                llPivots    = v.findViewById(R.id.llPivots);
                tvPP        = v.findViewById(R.id.tvPP);
                tvR1        = v.findViewById(R.id.tvR1);
                tvS1        = v.findViewById(R.id.tvS1);
                cgSignals   = v.findViewById(R.id.cgSignals);
            }
        }
    }
}
