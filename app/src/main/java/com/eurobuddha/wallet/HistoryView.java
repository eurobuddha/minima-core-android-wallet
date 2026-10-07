package com.eurobuddha.wallet;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * History tab: on-chain transactions that touched THIS wallet's 64 base addresses, read from the
 * node's {@code history relevant:true} pages and filtered client-side with {@link HistoryTx}
 * (the node's relevant set also contains the node wallet's own transactions — those are dropped).
 * Paged with "Load older"; the page size halves on an over-size reply. Tapping a row opens the
 * full detail (complete txpowid / counterparty address, per-token amounts — nothing truncated).
 */
public class HistoryView extends BaseView {

    private static final int FIRST_PAGE = 16;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.UK);

    private final LinearLayout list;
    private final Button older;
    private final TextView status;

    private final ArrayList<HistoryTx> mRows = new ArrayList<>();
    private int mOffset = 0;
    private int mPageSize = FIRST_PAGE;
    private boolean mLoading = false;
    private boolean mEnd = false;

    public HistoryView(MainActivity a) {
        super(a, buildRoot(a));
        LinearLayout content = (LinearLayout) ((ScrollView) root).getChildAt(0);
        status = (TextView) content.getChildAt(0);
        list   = (LinearLayout) content.getChildAt(1);
        older  = (Button) content.getChildAt(2);
        older.setOnClickListener(v -> loadPage());
        refresh();
    }

    private static View buildRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 16));
        content.setBackgroundColor(Design.bg());

        TextView status = new TextView(a);
        status.setTextColor(Design.dim());
        status.setTextSize(12f);
        content.addView(status);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Button older = new Button(a);
        older.setText("Load older");
        older.setTextColor(Design.accent());
        older.setVisibility(View.GONE);
        content.addView(older);

        sv.addView(content);
        return sv;
    }

    private static int dp(MainActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return dp(act, v); }

    @Override
    public void refresh() {
        if (mRows.isEmpty() && !mLoading) {
            reload();
        } else {
            render();
        }
    }

    @Override
    public void onShown() {
        reload();
    }

    @Override
    public void onNewBlock() {
        // A new block can confirm/append history — refresh only the first page view when idle.
        if (!mLoading) reload();
    }

    private void reload() {
        mRows.clear();
        mOffset = 0;
        mEnd = false;
        mPageSize = FIRST_PAGE;
        loadPage();
    }

    private void loadPage() {
        if (mLoading || mEnd || act.node() == null || !act.isPaired()) { render(); return; }
        mLoading = true;
        status.setText("Loading…");
        act.node().history(mPageSize, mOffset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                mLoading = false;
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                int raw = txpows == null ? 0 : txpows.length();
                if (txpows != null) {
                    for (int i = 0; i < raw; i++) {
                        JSONObject tp = txpows.optJSONObject(i);
                        if (tp == null) continue;
                        HistoryTx tx = HistoryTx.from(tp, act.addressBook());
                        if (tx.touchesOurs) mRows.add(tx);
                    }
                }
                mOffset += raw;
                if (raw < mPageSize) mEnd = true;
                render();
            }
            @Override public void onError(String message) {
                mLoading = false;
                // Over-size reply: halve the page and retry — a big wallet's history pages can
                // exceed the node's reply cap on nodes without the file hand-off.
                if (message != null && message.toLowerCase().contains("too long") && mPageSize > 1) {
                    mPageSize = Math.max(1, mPageSize / 2);
                    loadPage();
                    return;
                }
                status.setText("History unavailable: " + message);
            }
        });
    }

    private void render() {
        list.removeAllViews();
        if (mRows.isEmpty()) {
            status.setText(act.isPaired()
                    ? (mLoading ? "Loading…" : "No transactions at this wallet's addresses yet")
                    : "Pair with Minima Core to load history");
        } else {
            status.setText(mRows.size() + " transaction(s) at this wallet's addresses");
        }
        for (HistoryTx tx : mRows) {
            list.addView(row(tx));
        }
        older.setVisibility(mEnd || mRows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View row(final HistoryTx tx) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Design.surface());
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        String tid = tx.primaryTokenId();
        String dir = tx.direction();
        BigDecimal amt = tid == null ? BigDecimal.ZERO : tx.diff.get(tid);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView dirView = new TextView(act);
        dirView.setText(dir);
        dirView.setTextSize(11f);
        dirView.setLetterSpacing(0.08f);
        dirView.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        dirView.setTextColor("RECEIVED".equals(dir) ? Design.accent() : Design.dim());
        top.addView(dirView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView amtView = new TextView(act);
        amtView.setText(tid == null ? "—"
                : Util.tidyAmount(amt.toPlainString()) + "  " + tx.tokenName(tid));
        amtView.setTextColor(Design.text());
        amtView.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        amtView.setTextSize(14f);
        amtView.setGravity(Gravity.END);
        top.addView(amtView);
        card.addView(top);

        TextView sub = new TextView(act);
        sub.setText(FMT.format(new Date(tx.timemilli)) + "  ·  block " + tx.block);
        sub.setTextColor(Design.dim());
        sub.setTextSize(11f);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);

        card.setOnClickListener(v -> showDetail(tx));
        return card;
    }

    /** Full detail — complete identifiers, selectable, nothing shortened. */
    private void showDetail(HistoryTx tx) {
        StringBuilder sb = new StringBuilder();
        sb.append(tx.direction()).append("\n");
        sb.append(FMT.format(new Date(tx.timemilli))).append("  ·  block ").append(tx.block).append("\n\n");
        for (Map.Entry<String, BigDecimal> e : tx.diff.entrySet()) {
            sb.append(Util.tidyAmount(e.getValue().toPlainString()))
              .append("  ").append(tx.tokenName(e.getKey())).append("\n");
            if (!Util.isMinima(e.getKey())) sb.append("tokenid: ").append(e.getKey()).append("\n");
        }
        if (!tx.counterparty.isEmpty()) {
            sb.append("\ncounterparty:\n").append(tx.counterparty).append("\n");
        }
        sb.append("\ntxpowid:\n").append(tx.txpowid);

        TextView msg = new TextView(act);
        msg.setText(sb.toString());
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(16), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Transaction")
                .setView(wrap)
                .setPositiveButton("Copy txpowid", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("txpowid", tx.txpowid));
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
