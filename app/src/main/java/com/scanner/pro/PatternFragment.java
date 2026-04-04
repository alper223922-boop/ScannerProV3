package com.scanner.pro;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;

import com.google.android.material.chip.*;

import java.util.*;
import java.util.concurrent.*;

public class PatternFragment extends Fragment {

    private static final String[] TF_LABELS = {"5m","15m","30m","1h","2h","4h","1d"};

    private ChipGroup cgTimeframe;
    private RadioGroup rgDir;
    private Button btnScan;
    private ProgressBar progressBar;
    private TextView tvProgress, tvResultCount;
    private RecyclerView recyclerView;

    private final List<PatternResult> results = new ArrayList<>();
    private PatternAdapter adapter;
    private volatile boolean scanning = false;
    private ExecutorService executor;

    public static class PatternResult {
        String symbol;
        List<PatternDetector.Pattern> patterns = new ArrayList<>();
        double price, changePercent, volume;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_patterns, container, false);

        cgTimeframe  = v.findViewById(R.id.cgTimeframe);
        rgDir        = v.findViewById(R.id.rgDir);
        btnScan      = v.findViewById(R.id.btnScan);
        progressBar  = v.findViewById(R.id.progressBar);
        tvProgress   = v.findViewById(R.id.tvProgress);
        tvResultCount= v.findViewById(R.id.tvResultCount);
        recyclerView = v.findViewById(R.id.recyclerView);

        for (String tf : TF_LABELS) {
            Chip chip = new Chip(requireContext());
            chip.setText(tf); chip.setTag(tf);
            chip.setCheckable(true); chip.setChecked(tf.equals("1h"));
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#080E1A")));
            chip.setTextColor(android.graphics.Color.parseColor("#4E7399"));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E3358")));
            chip.setChipStrokeWidth(1f); chip.setTextSize(10f);
            cgTimeframe.addView(chip);
        }

        adapter = new PatternAdapter(results);
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
        if (id == R.id.rbBull) return "bullish";
        if (id == R.id.rbBear) return "bearish";
        return "all";
    }

    private void startScan() {
        scanning = true;
        results.clear(); adapter.notifyDataSetChanged();
        btnScan.setText("⏹ DURDUR");
        btnScan.setBackgroundColor(Color.parseColor("#3D1A00"));
        btnScan.setTextColor(Color.parseColor("#F0B90B"));
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);

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
                    tvProgress.setText(prog + " / " + total + " tarıyor...");
                    progressBar.setProgress((int)(prog * 100f / total));
                });

                try {
                    List<BinanceApi.Kline> klines = BinanceApi.getKlines(sym, tf, 60);
                    int sz = klines.size();
                    PatternDetector.Candle[] candles = new PatternDetector.Candle[sz];
                    double[] highs = new double[sz], lows = new double[sz], closes = new double[sz];
                    for (int k = 0; k < sz; k++) {
                        BinanceApi.Kline kl = klines.get(k);
                        candles[k] = new PatternDetector.Candle(kl.open, kl.high, kl.low, kl.close);
                        highs[k] = kl.high; lows[k] = kl.low; closes[k] = kl.close;
                    }

                    List<PatternDetector.Pattern> patterns = PatternDetector.detect(candles, 0);
                    if (patterns.isEmpty()) continue;

                    // Calculate ATR for price targets
                    double[] atrArr = MathUtils.atr(highs, lows, closes, 14);
                    double atrVal = atrArr[sz - 1];
                    if (Double.isNaN(atrVal)) atrVal = closes[sz - 1] * 0.01;

                    // Recalculate with real ATR
                    patterns = PatternDetector.detect(candles, atrVal);

                    // Apply direction filter
                    if (!dirFilter.equals("all")) {
                        List<PatternDetector.Pattern> filtered = new ArrayList<>();
                        for (PatternDetector.Pattern p : patterns)
                            if (p.direction.equals(dirFilter)) filtered.add(p);
                        patterns = filtered;
                    }
                    if (patterns.isEmpty()) continue;

                    BinanceApi.Ticker tk = tickerMap.get(sym);
                    PatternResult res = new PatternResult();
                    res.symbol = sym;
                    res.patterns = patterns;
                    res.price = tk != null ? tk.lastPrice : 0;
                    res.changePercent = tk != null ? tk.priceChangePercent : 0;
                    res.volume = tk != null ? tk.quoteVolume : 0;

                    final List<PatternDetector.Pattern> finalPatterns = patterns;
                    requireActivity().runOnUiThread(() -> {
                        results.add(res);
                        adapter.notifyItemInserted(results.size() - 1);
                        tvResultCount.setText(results.size() + " SEMBOL • " +
                                results.stream().mapToInt(r -> r.patterns.size()).sum() + " FORMASYON");
                    });
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
        requireActivity().runOnUiThread(this::resetUI);
    }

    private void resetUI() {
        scanning = false;
        btnScan.setText("▶ TARA");
        btnScan.setBackgroundColor(Color.parseColor("#F0B90B"));
        btnScan.setTextColor(Color.BLACK);
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    // ── Adapter ──────────────────────────────────────────────────────────────
    static class PatternAdapter extends RecyclerView.Adapter<PatternAdapter.VH> {
        private final List<PatternResult> data;
        PatternAdapter(List<PatternResult> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pattern_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            PatternResult r = data.get(pos);
            h.tvSymbol.setText(r.symbol);
            h.tvPrice.setText(r.price < 1
                    ? String.format("$%.5f", r.price) : String.format("$%.2f", r.price));

            h.llPatterns.removeAllViews();
            for (PatternDetector.Pattern p : r.patterns) {
                // Pattern row
                LinearLayout row = new LinearLayout(h.itemView.getContext());
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(12, 8, 12, 8);
                int bgColor = p.direction.equals("bullish") ? Color.parseColor("#0D1A0D")
                        : p.direction.equals("bearish") ? Color.parseColor("#1A0D0D")
                        : Color.parseColor("#060B14");
                row.setBackgroundColor(bgColor);

                // Pattern name + direction badge
                LinearLayout nameRow = new LinearLayout(h.itemView.getContext());
                nameRow.setOrientation(LinearLayout.HORIZONTAL);
                nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView tvName = new TextView(h.itemView.getContext());
                String arrow = p.direction.equals("bullish") ? "▲" : p.direction.equals("bearish") ? "▼" : "◆";
                tvName.setText(arrow + " " + p.name);
                tvName.setTextColor(Color.parseColor(
                        p.direction.equals("bullish") ? "#00E676" : p.direction.equals("bearish") ? "#FF4060" : "#F0B90B"));
                tvName.setTextSize(12f);
                tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                nameRow.addView(tvName);

                TextView tvBadge = new TextView(h.itemView.getContext());
                tvBadge.setText(p.direction.equals("bullish") ? "YUKARI BASKISI"
                        : p.direction.equals("bearish") ? "AŞAĞI BASKISI" : "NÖTR");
                tvBadge.setTextColor(Color.parseColor(
                        p.direction.equals("bullish") ? "#00E676" : p.direction.equals("bearish") ? "#FF4060" : "#F0B90B"));
                tvBadge.setTextSize(9f);
                tvBadge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                nameRow.addView(tvBadge);
                row.addView(nameRow);

                // Targets row
                LinearLayout targetsRow = new LinearLayout(h.itemView.getContext());
                targetsRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 6, 0, 0);
                targetsRow.setLayoutParams(lp);
                targetsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

                addTargetChip(targetsRow, h.itemView.getContext(), "TP1",
                        formatPrice(p.tp1), "#00E676", "#001F0E");
                addTargetChip(targetsRow, h.itemView.getContext(), "TP2",
                        formatPrice(p.tp2), "#00E676", "#0A2A0A");
                addTargetChip(targetsRow, h.itemView.getContext(), " SL",
                        formatPrice(p.stopLoss), "#FF4060", "#1F0008");
                row.addView(targetsRow);

                // Add margin
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, 6);
                row.setLayoutParams(rowLp);

                h.llPatterns.addView(row);
            }

            h.tvChange.setText(String.format("%+.2f%%", r.changePercent));
            h.tvChange.setTextColor(r.changePercent >= 0
                    ? Color.parseColor("#00E676") : Color.parseColor("#FF4060"));
            h.tvVolume.setText(String.format("Vol: $%.1fM", r.volume / 1_000_000));
        }

        private String formatPrice(double p) {
            return p < 1 ? String.format("$%.5f", p) : String.format("$%.2f", p);
        }

        private void addTargetChip(LinearLayout parent, android.content.Context ctx,
                                   String label, String value, String textColor, String bgColor) {
            TextView tv = new TextView(ctx);
            tv.setText(label + " " + value);
            tv.setTextColor(Color.parseColor(textColor));
            tv.setBackgroundColor(Color.parseColor(bgColor));
            tv.setTextSize(9f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(10, 4, 10, 4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            tv.setLayoutParams(lp);
            parent.addView(tv);
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSymbol, tvPrice, tvChange, tvVolume;
            LinearLayout llPatterns;
            VH(View v) {
                super(v);
                tvSymbol   = v.findViewById(R.id.tvSymbol);
                tvPrice    = v.findViewById(R.id.tvPrice);
                tvChange   = v.findViewById(R.id.tvChange);
                tvVolume   = v.findViewById(R.id.tvVolume);
                llPatterns = v.findViewById(R.id.llPatterns);
            }
        }
    }
}
