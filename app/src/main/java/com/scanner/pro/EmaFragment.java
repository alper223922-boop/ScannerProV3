package com.scanner.pro;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.*;
import java.util.concurrent.*;

public class EmaFragment extends Fragment {

    private static final String[] TF_LABELS = {"5m","15m","30m","1h","2h","4h","1d"};

    private RadioGroup rgType, rgFilter;
    private EditText etFast, etSlow, etFreshness;
    private ChipGroup cgTimeframes;
    private Button btnScan;
    private ProgressBar progressBar;
    private TextView tvProgress, tvResultCount;
    private RecyclerView recyclerView;

    private final List<EmaResult> results = new ArrayList<>();
    private EmaAdapter adapter;
    private volatile boolean scanning = false;
    private ExecutorService executor;

    public static class TfResult {
        String tf, cross;
        int barsAgo;
        TfResult(String tf, String cross, int barsAgo) {
            this.tf = tf; this.cross = cross; this.barsAgo = barsAgo;
        }
    }

    public static class EmaResult {
        String symbol;
        List<TfResult> tfResults = new ArrayList<>();
        double price, changePercent, quoteVolume;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_ema, container, false);
        rgType        = v.findViewById(R.id.rgType);
        rgFilter      = v.findViewById(R.id.rgFilter);
        etFast        = v.findViewById(R.id.etFast);
        etSlow        = v.findViewById(R.id.etSlow);
        etFreshness   = v.findViewById(R.id.etFreshness);
        cgTimeframes  = v.findViewById(R.id.cgTimeframes);
        btnScan       = v.findViewById(R.id.btnScan);
        progressBar   = v.findViewById(R.id.progressBar);
        tvProgress    = v.findViewById(R.id.tvProgress);
        tvResultCount = v.findViewById(R.id.tvResultCount);
        recyclerView  = v.findViewById(R.id.recyclerView);

        for (String tf : TF_LABELS) {
            Chip chip = new Chip(requireContext());
            chip.setText(tf); chip.setTag(tf); chip.setCheckable(true);
            chip.setChecked(tf.equals("1h") || tf.equals("4h"));
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#080E1A")));
            chip.setTextColor(android.graphics.Color.parseColor("#4E7399"));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E3358")));
            chip.setChipStrokeWidth(1f); chip.setTextSize(10f);
            cgTimeframes.addView(chip);
        }

        adapter = new EmaAdapter(results);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        btnScan.setOnClickListener(x -> { if (scanning) stopScan(); else startScan(); });
        return v;
    }

    private List<String> getSelectedTfs() {
        List<String> tfs = new ArrayList<>();
        for (int i = 0; i < cgTimeframes.getChildCount(); i++) {
            Chip c = (Chip) cgTimeframes.getChildAt(i);
            if (c.isChecked()) tfs.add((String) c.getTag());
        }
        return tfs;
    }

    private String getFilter() {
        int id = rgFilter.getCheckedRadioButtonId();
        if (id == R.id.rbGolden) return "golden";
        if (id == R.id.rbDeath)  return "death";
        return "all";
    }

    private String getType() {
        int id = rgType.getCheckedRadioButtonId();
        if (id == R.id.rbSMA)  return "SMA";
        if (id == R.id.rbDEMA) return "DEMA";
        return "EMA";
    }

    private void startScan() {
        List<String> tfs = getSelectedTfs();
        if (tfs.isEmpty()) {
            Toast.makeText(requireContext(), "En az 1 zaman dilimi sec!", Toast.LENGTH_SHORT).show();
            return;
        }
        int fast, slow;
        try {
            fast = Integer.parseInt(etFast.getText().toString());
            slow = Integer.parseInt(etSlow.getText().toString());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Gecerli period gir!", Toast.LENGTH_SHORT).show();
            return;
        }
        int freshness = 5;
        try { freshness = Integer.parseInt(etFreshness.getText().toString()); } catch (Exception ignored) {}

        scanning = true;
        results.clear(); adapter.notifyDataSetChanged();
        btnScan.setText("DURDUR");
        btnScan.setBackgroundColor(Color.parseColor("#3D1A00"));
        btnScan.setTextColor(Color.parseColor("#F0B90B"));
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvResultCount.setText("0 SONUC");

        final String type = getType();
        final String filter = getFilter();
        final int finalFreshness = freshness;
        final int finalFast = fast;
        final int finalSlow = slow;

        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runScan(type, finalFast, finalSlow, tfs, filter, finalFreshness));
    }

    private void stopScan() {
        scanning = false;
        if (executor != null) executor.shutdownNow();
        resetScanUI();
    }

    private void runScan(String type, int fast, int slow, List<String> tfs,
                         String filter, int freshness) {
        try {
            List<String> symbols = BinanceApi.getSymbols();
            List<BinanceApi.Ticker> tickers = BinanceApi.get24hrAll();
            Map<String, BinanceApi.Ticker> tickerMap = new HashMap<>();
            for (BinanceApi.Ticker t : tickers) tickerMap.put(t.symbol, t);

            int total = symbols.size();
            // DEMA icin 2x ema gerekir, limit buna gore hesapla
            int limit = (type.equals("DEMA") ? slow * 2 : slow) + freshness + 10;

            for (int i = 0; i < symbols.size() && scanning; i++) {
                String sym = symbols.get(i);
                final int prog = i + 1;
                requireActivity().runOnUiThread(() -> {
                    tvProgress.setText(prog + " / " + total);
                    progressBar.setProgress((int)(prog * 100f / total));
                });

                BinanceApi.Ticker tk = tickerMap.get(sym);
                if (tk == null) continue;

                List<TfResult> tfResults = new ArrayList<>();

                for (String tf : tfs) {
                    if (!scanning) break;
                    try {
                        List<BinanceApi.Kline> klines = BinanceApi.getKlines(sym, tf, limit);
                        if (klines.size() < slow + 2) continue;

                        double[] closes = new double[klines.size()];
                        for (int k = 0; k < klines.size(); k++) closes[k] = klines.get(k).close;

                        double[] fastLine, slowLine;
                        switch (type) {
                            case "SMA":
                                fastLine = MathUtils.sma(closes, fast);
                                slowLine = MathUtils.sma(closes, slow);
                                break;
                            case "DEMA":
                                fastLine = MathUtils.dema(closes, fast);
                                slowLine = MathUtils.dema(closes, slow);
                                break;
                            default:
                                fastLine = MathUtils.ema(closes, fast);
                                slowLine = MathUtils.ema(closes, slow);
                                break;
                        }

                        // Son N mumda kesisim ara
                        int n = closes.length - 1;
                        for (int b = 0; b <= freshness && b < n - 1; b++) {
                            int idx = n - b;
                            if (Double.isNaN(fastLine[idx]) || Double.isNaN(slowLine[idx]) ||
                                Double.isNaN(fastLine[idx-1]) || Double.isNaN(slowLine[idx-1])) continue;

                            String cross = null;
                            if (fastLine[idx-1] <= slowLine[idx-1] && fastLine[idx] > slowLine[idx])
                                cross = "golden";
                            else if (fastLine[idx-1] >= slowLine[idx-1] && fastLine[idx] < slowLine[idx])
                                cross = "death";

                            if (cross == null) continue;
                            if (!filter.equals("all") && !cross.equals(filter)) break;
                            tfResults.add(new TfResult(tf, cross, b));
                            break;
                        }

                    } catch (Exception ignored) {}
                }

                if (tfResults.isEmpty()) continue;

                EmaResult res = new EmaResult();
                res.symbol = sym;
                res.tfResults = tfResults;
                res.price = tk.lastPrice;
                res.changePercent = tk.priceChangePercent;
                res.quoteVolume = tk.quoteVolume;

                requireActivity().runOnUiThread(() -> {
                    results.add(res);
                    adapter.notifyItemInserted(results.size() - 1);
                    tvResultCount.setText(results.size() + " SONUC");
                });
            }
        } catch (Exception e) {
            requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
        requireActivity().runOnUiThread(this::resetScanUI);
    }

    private void resetScanUI() {
        scanning = false;
        btnScan.setText("TARA");
        btnScan.setBackgroundColor(Color.parseColor("#F0B90B"));
        btnScan.setTextColor(Color.BLACK);
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    static class EmaAdapter extends RecyclerView.Adapter<EmaAdapter.VH> {
        private final List<EmaResult> data;
        EmaAdapter(List<EmaResult> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_ema_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            EmaResult r = data.get(pos);

            // Sembol + fiyat
            h.tvSymbol.setText(r.symbol);
            h.tvPrice.setText(r.price < 1
                    ? String.format("$%.5f", r.price)
                    : String.format("$%.2f", r.price));

            // TF chip'leri
            h.cgTfResults.removeAllViews();
            for (TfResult tr : r.tfResults) {
                Chip chip = new Chip(h.itemView.getContext());
                boolean isGolden = tr.cross.equals("golden");
                String barsText = tr.barsAgo == 0 ? "simdi" : tr.barsAgo + " mum once";
                chip.setText(tr.tf + " " + (isGolden ? "GOLDEN" : "DEATH") + " [" + barsText + "]");
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                        Color.parseColor(isGolden ? "#001F0E" : "#1F0008")));
                chip.setTextColor(Color.parseColor(isGolden ? "#00E676" : "#FF4060"));
                chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                        Color.parseColor(isGolden ? "#00E676" : "#FF4060")));
                chip.setChipStrokeWidth(1f);
                chip.setTextSize(9f);
                chip.setClickable(false);
                h.cgTfResults.addView(chip);
            }

            // Gunluk fiyat degisimi - buyuk ve renkli
            String changeTxt = String.format("%+.2f%%", r.changePercent);
            h.tvChange.setText("24h: " + changeTxt);
            h.tvChange.setTextColor(r.changePercent >= 0
                    ? Color.parseColor("#00E676") : Color.parseColor("#FF4060"));

            h.tvVolume.setText(String.format("Vol: $%.1fM", r.quoteVolume / 1_000_000));
            h.tvVolume.setTextColor(Color.parseColor("#4E7399"));
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSymbol, tvPrice, tvChange, tvVolume;
            ChipGroup cgTfResults;
            VH(View v) {
                super(v);
                tvSymbol    = v.findViewById(R.id.tvSymbol);
                tvPrice     = v.findViewById(R.id.tvPrice);
                tvChange    = v.findViewById(R.id.tvChange);
                tvVolume    = v.findViewById(R.id.tvVolume);
                cgTfResults = v.findViewById(R.id.cgTfResults);
            }
        }
    }
}
