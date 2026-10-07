package com.eurobuddha.wallet;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.minima.utils.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * NFTs tab — the artwork leads, the interface recedes.
 *
 * <pre>
 *   Gallery      adaptive artwork grid of collections (alphabetical)
 *   Collection   full-screen browser: cover banner, description, item grid,
 *                per-item SEND, Send collection
 *   Viewer       full-screen: pinch-zoom full resolution, swipe left/right
 *                between items, single-tap hides/shows the chrome
 *   Details      every field, full identifiers, copyable
 * </pre>
 *
 * Send safety (every path):
 * <ul>
 *   <li><b>Double-send guard</b> — a coin broadcast this session is PENDING until the chain
 *       confirms the spend (it drops out of the coins list). Pending items show a SENDING chip
 *       and refuse further sends; a stale dialog can never resell a sent coin because dialogs
 *       close on success and every send re-checks the live coin list by coinid.</li>
 *   <li><b>Unstamped locked editions are refused</b> — the creator bypass is still live on such
 *       a coin, so a recipient could have it reclaimed. Collection sends skip them and say so.</li>
 *   <li><b>Self-send notice</b> — sending to one of this wallet's own 64 addresses is flagged in
 *       the review (allowed, but never silent).</li>
 *   <li>A StateNFT's sealed state must equal the transaction state, so a collection send is N
 *       separate locally-signed transactions, sequential, with progress, stop, and a full
 *       per-txpowid report.</li>
 * </ul>
 * Display and SEND only — minting stays in NFT wallet.
 */
public class NftView extends BaseView {

    /** coinid → broadcast time, for coins sent this session and not yet confirmed spent. A txn
     *  can fail consensus SILENTLY after txnpost, so entries expire after a TTL and the item
     *  becomes sendable again instead of being stuck "SENDING" forever. */
    private static final java.util.HashMap<String, Long> PENDING = new java.util.HashMap<>();
    private static final long PENDING_TTL_MS = 10 * 60_000;

    private static boolean isPending(String zCoinId) {
        Long t = PENDING.get(zCoinId);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > PENDING_TTL_MS) { PENDING.remove(zCoinId); return false; }
        return true;
    }

    private final LinearLayout grid;
    private final TextView status;

    /** The open browser/viewer dialogs — closed after a successful send so they can't go stale. */
    private Dialog mBrowser, mViewer;

    public NftView(MainActivity a) {
        super(a, buildRoot(a));
        LinearLayout content = (LinearLayout) ((ScrollView) root).getChildAt(0);
        status = (TextView) content.getChildAt(0);
        grid   = (LinearLayout) content.getChildAt(1);
        refresh();
    }

    private static View buildRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 16));
        content.setBackgroundColor(Design.bg());
        TextView status = new TextView(a);
        status.setTextColor(Design.dim());
        status.setTextSize(12f);
        status.setPadding(dp(a, 4), 0, dp(a, 4), dp(a, 4));
        content.addView(status);
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        content.addView(grid);
        sv.addView(content);
        return sv;
    }

    private static int dp(MainActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return dp(act, v); }

    /** Columns from the real screen width — 2 on a phone, more on the Fold's inner display. */
    private int columns() {
        float wdp = act.getResources().getDisplayMetrics().widthPixels
                / act.getResources().getDisplayMetrics().density;
        return Math.max(2, (int) (wdp / 190));
    }

    /** Square grid cell image: height always equals measured width. */
    private static class SquareImageView extends androidx.appcompat.widget.AppCompatImageView {
        SquareImageView(MainActivity a) { super(a); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }
    }

    /** One owned NFT token: its meta and our coins of it, sorted by sealed index. */
    private static class Group {
        String tokenid;
        Nft.Meta meta;
        final List<JSONObject> coins = new ArrayList<>();
    }

    /** Lay {@code cells} out in {@code cols} equal columns, padding the last row. */
    private void addGrid(LinearLayout parent, List<View> cells, int cols) {
        for (int i = 0; i < cells.size(); i += cols) {
            LinearLayout rowv = new LinearLayout(act);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                View cell = (i + c < cells.size()) ? cells.get(i + c) : new View(act);
                rowv.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            parent.addView(rowv);
        }
    }

    // =============================================================================================
    // Gallery (tab content)
    // =============================================================================================

    @Override
    public void refresh() {
        grid.removeAllViews();

        org.minima.utils.json.JSONArray all = act.coins();
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        HashSet<String> liveCoinIds = new HashSet<>();
        if (all != null) {
            for (Object o : all) {
                JSONObject c = (JSONObject) o;
                liveCoinIds.add(Nft.str(c, "coinid"));
                String tokenid = Nft.str(c, "tokenid");
                if (Util.isMinima(tokenid)) continue;
                Object tok = c.get("token");
                Nft.Meta meta = Nft.meta(tok instanceof JSONObject ? (JSONObject) tok : null);
                if (!Nft.isNftMeta(meta)) continue;
                Group g = groups.get(tokenid);
                if (g == null) { g = new Group(); g.tokenid = tokenid; g.meta = meta; groups.put(tokenid, g); }
                g.coins.add(c);
            }
        }
        // A pending coin that has left the live list is confirmed spent — stop tracking it.
        PENDING.keySet().retainAll(liveCoinIds);

        List<Group> list = new ArrayList<>(groups.values());
        for (Group g : list) {
            java.util.Collections.sort(g.coins, (a, b) -> Nft.itemIndex(a) - Nft.itemIndex(b));
        }
        java.util.Collections.sort(list, (a, b) -> a.meta.name.compareToIgnoreCase(b.meta.name));

        if (list.isEmpty()) {
            status.setText(act.isPaired()
                    ? "No NFTs at this wallet's addresses yet — anything sent to your Receive addresses appears here"
                    : "Pair with Minima Core to load your NFTs");
            return;
        }
        status.setText(list.size() + " collection(s) · tap artwork to browse");

        List<View> cells = new ArrayList<>();
        for (Group g : list) cells.add(galleryTile(g));
        addGrid(grid, cells, columns());
    }

    private View galleryTile(final Group g) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(10));

        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        art.setContentDescription(g.meta.name + ", " + g.coins.size() + " items");
        art.setImageBitmap(Identicon.forToken(g.tokenid, dp(160)));
        loadArt(g, g.coins.get(0), art, false);
        cell.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(act);
        name.setText(g.meta.name);
        name.setTextColor(Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(14f);
        name.setMaxLines(1);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        cell.addView(name);

        TextView sub = new TextView(act);
        sub.setText((g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size()
                + (g.coins.size() == 1 ? " item" : " items"));
        sub.setTextColor(Design.dim());
        sub.setTextSize(11f);
        sub.setPadding(dp(2), 0, dp(2), 0);
        cell.addView(sub);

        cell.setOnClickListener(v -> openCollection(g));
        return cell;
    }

    private void loadArt(Group g, JSONObject coin, ImageView iv, boolean full) {
        String url = Nft.imageUrl(g.meta, Nft.itemIndex(coin), coin);
        if (url == null || url.isEmpty()) return;
        if (full) ImageLoader.loadFullOver(act, url, iv, null);
        else      ImageLoader.loadTile(act, url, iv, null);
    }

    // =============================================================================================
    // Collection browser (full screen)
    // =============================================================================================

    private void openCollection(final Group g) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        mBrowser = d;
        d.setOnDismissListener(x -> { if (mBrowser == d) mBrowser = null; });

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Design.bg());
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);

        // Cover banner — the collection's own artwork carries the header; title sits on a scrim.
        FrameLayout banner = new FrameLayout(act);
        ImageView cover = new ImageView(act);
        cover.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210)));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Design.surface());
        cover.setImageBitmap(Identicon.forToken(g.tokenid, dp(210)));
        loadArt(g, g.coins.get(0), cover, false);
        banner.addView(cover);
        View scrim = new View(act);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0xB3000000}));
        banner.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210)));
        TextView back = new TextView(act);
        back.setText("←");
        back.setTextSize(26f);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(14), dp(8), dp(16), dp(12));
        back.setContentDescription("Back to gallery");
        back.setOnClickListener(v -> d.dismiss());
        banner.addView(back, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(16), 0, dp(16), dp(12));
        TextView title = new TextView(act);
        title.setText(g.meta.name);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(22f);
        titleCol.addView(title);
        TextView count = new TextView(act);
        count.setText((g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size()
                + (g.coins.size() == 1 ? " item held here" : " items held here"));
        count.setTextColor(0xE6FFFFFF);
        count.setTextSize(12f);
        titleCol.addView(count);
        banner.addView(titleCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        content.addView(banner);

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(16));

        if (!g.meta.description.isEmpty()) {
            TextView desc = new TextView(act);
            desc.setText(g.meta.description);
            desc.setTextColor(Design.text());
            desc.setTextSize(13f);
            desc.setPadding(dp(4), 0, dp(4), dp(10));
            body.addView(desc);
        }

        Button sendAll = new Button(act);
        sendAll.setText("Send collection (" + g.coins.size() + ")");
        sendAll.setTextColor(Design.onAccent());
        sendAll.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        sendAll.setOnClickListener(v -> promptSendCollection(g));
        body.addView(sendAll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        List<View> cells = new ArrayList<>();
        for (JSONObject coin : g.coins) cells.add(itemTile(g, coin));
        LinearLayout itemsGrid = new LinearLayout(act);
        itemsGrid.setOrientation(LinearLayout.VERTICAL);
        itemsGrid.setPadding(0, dp(8), 0, 0);
        addGrid(itemsGrid, cells, columns());
        body.addView(itemsGrid);

        content.addView(body);
        sv.addView(content);
        d.setContentView(sv);
        d.show();
    }

    private View itemTile(final Group g, final JSONObject coin) {
        final String coinid = Nft.str(coin, "coinid");
        final boolean pending = isPending(coinid);
        final int idx = Nft.itemIndex(coin);
        final int ordinal = g.coins.indexOf(coin) + 1;
        final String itemName = idx >= 0 ? "#" + idx : "item " + ordinal;

        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(10));

        FrameLayout artFrame = new FrameLayout(act);
        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        art.setContentDescription(g.meta.name + " " + itemName);
        art.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(160)));
        loadArt(g, coin, art, false);
        artFrame.addView(art, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        if (pending) {
            TextView chip = new TextView(act);
            chip.setText("SENDING");
            chip.setTextSize(10f);
            chip.setLetterSpacing(0.08f);
            chip.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundColor(0xB3000000);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            artFrame.addView(chip, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END));
        }
        cell.addView(artFrame);

        LinearLayout caption = new LinearLayout(act);
        caption.setOrientation(LinearLayout.HORIZONTAL);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(act);
        label.setText(itemName);
        label.setTextColor(Design.text());
        label.setTextSize(13f);
        label.setPadding(dp(2), dp(4), dp(2), 0);
        caption.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView sendOne = new TextView(act);
        sendOne.setText(pending ? "SENDING…" : "SEND");
        sendOne.setTextSize(12f);
        sendOne.setLetterSpacing(0.06f);
        sendOne.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        sendOne.setTextColor(pending ? Design.dim() : Design.accent());
        sendOne.setMinHeight(dp(40));
        sendOne.setGravity(Gravity.CENTER_VERTICAL);
        sendOne.setPadding(dp(12), 0, dp(4), 0);
        sendOne.setContentDescription("Send " + g.meta.name + " " + itemName);
        if (!pending) sendOne.setOnClickListener(v -> promptSendItem(g, coin));
        caption.addView(sendOne);
        cell.addView(caption);

        cell.setOnClickListener(v -> openViewer(g, g.coins.indexOf(coin)));
        return cell;
    }

    // =============================================================================================
    // Item viewer (full screen, pinch-zoom, swipe, tap-to-immerse)
    // =============================================================================================

    private void openViewer(final Group g, int zStartPos) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        mViewer = d;
        d.setOnDismissListener(x -> { if (mViewer == d) mViewer = null; });
        final int[] pos = { Math.max(0, zStartPos) };

        FrameLayout frame = new FrameLayout(act);
        frame.setBackgroundColor(Color.BLACK);

        final ZoomImageView zoom = new ZoomImageView(act);
        frame.addView(zoom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar: back · title · position counter.
        final LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView back = new TextView(act);
        back.setText("←");
        back.setTextSize(24f);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(4), 0, dp(14), 0);
        back.setContentDescription("Back to collection");
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        final TextView title = new TextView(act);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(16f);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView counter = new TextView(act);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(13f);
        counter.setPadding(dp(8), 0, dp(8), 0);
        top.addView(counter);
        frame.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bottom bar: Details · Send — always the CURRENT item.
        final LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(6));
        Button details = new Button(act);
        details.setText("Details");
        details.setTextColor(Color.WHITE);
        details.setBackgroundColor(Color.TRANSPARENT);
        details.setOnClickListener(v -> showDetails(g, g.coins.get(pos[0])));
        bottom.addView(details, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final Button send = new Button(act);
        send.setTextColor(Design.onAccent());
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        send.setOnClickListener(v -> promptSendItem(g, g.coins.get(pos[0])));
        bottom.addView(send, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        frame.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Show one item in place — image, title, counter, send state.
        final Runnable show = () -> {
            JSONObject coin = g.coins.get(pos[0]);
            int idx = Nft.itemIndex(coin);
            boolean pending = isPending(Nft.str(coin, "coinid"));
            title.setText(g.meta.name + (idx >= 0 ? "  #" + idx : ""));
            counter.setText((pos[0] + 1) + " / " + g.coins.size());
            send.setText(pending ? "Sending…" : "Send");
            send.setEnabled(!pending);
            send.setAlpha(pending ? 0.5f : 1f);
            zoom.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(320)));
            loadArt(g, coin, zoom, true);   // full resolution (bounded 1600px)
        };

        // Swipe left/right at 1x = next/previous; zoomed-in drags stay panning. Ends stop.
        zoom.setSwipeListener(next -> {
            int to = pos[0] + (next ? 1 : -1);
            if (to < 0 || to >= g.coins.size()) return;
            pos[0] = to;
            show.run();
        });

        // Single tap: hide/show the chrome so the artwork owns the screen.
        zoom.setTapListener(() -> {
            int vis = top.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            top.setVisibility(vis);
            bottom.setVisibility(vis);
        });

        show.run();
        d.setContentView(frame);
        d.show();
    }

    // =============================================================================================
    // Details — every field, full identifiers, copyable
    // =============================================================================================

    private void showDetails(final Group g, final JSONObject coin) {
        int idx = Nft.itemIndex(coin);
        String addr = Nft.str(coin, "address");
        Integer ki = act.keyIndexForAddress(addr);

        StringBuilder sb = new StringBuilder();
        sb.append(g.meta.name).append(idx >= 0 ? "  #" + idx : "").append("\n");
        sb.append(g.meta.stateNft ? "StateNFT (locked edition)" : "NFT").append("\n");
        if (!g.meta.mode.isEmpty()) sb.append("artwork mode: ").append(g.meta.mode).append("\n");
        if (isPending(Nft.str(coin, "coinid"))) {
            sb.append("status: SENDING — broadcast, awaiting chain confirmation\n");
        }
        if (Nft.isUnstampedLocked(g.meta, coin)) {
            sb.append("status: UNSTAMPED — not sendable (the creator bypass is still live)\n");
        }
        if (!g.meta.description.isEmpty()) sb.append("\n").append(g.meta.description).append("\n");
        sb.append("\ntokenid:\n").append(g.tokenid).append("\n");
        sb.append("\ncoinid:\n").append(Nft.str(coin, "coinid")).append("\n");
        sb.append("\nheld at address").append(ki != null ? " (key " + ki + ")" : "").append(":\n")
          .append(addr).append("\n");
        sb.append("\namount: ").append(Nft.str(coin, "tokenamount").isEmpty()
                ? Nft.str(coin, "amount") : Nft.str(coin, "tokenamount")).append("\n");
        sb.append("created block: ").append(Nft.str(coin, "created")).append("\n");

        List<String[]> state = Nft.rawStateEntries(coin);
        if (!state.isEmpty()) {
            sb.append("\nstate:\n");
            for (String[] e : state) {
                String v = e[1];
                if ("1".equals(e[0]) && v.length() > 120) {
                    sb.append("  port 1: [embedded artwork, ").append(v.length()).append(" chars]\n");
                } else {
                    sb.append("  port ").append(e[0]).append(": ").append(v).append("\n");
                }
            }
        }

        TextView msg = new TextView(act);
        msg.setText(sb.toString());
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Details")
                .setView(wrap)
                .setPositiveButton("Copy tokenid", (dd, w) -> copy("tokenid", g.tokenid))
                .setNeutralButton("Copy coinid", (dd, w) -> copy("coinid", Nft.str(coin, "coinid")))
                .setNegativeButton("Close", null)
                .show();
    }

    private void copy(String zLabel, String zValue) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(zLabel, zValue));
        Toast.makeText(act, zLabel + " copied", Toast.LENGTH_SHORT).show();
    }

    // =============================================================================================
    // Send one item
    // =============================================================================================

    private void promptSendItem(final Group g, final JSONObject coin) {
        final int idx = Nft.itemIndex(coin);
        String guard = sendGuard(g, coin);
        if (guard != null) { Toast.makeText(act, guard, Toast.LENGTH_LONG).show(); return; }
        promptRecipient("Send " + g.meta.name + (idx >= 0 ? " #" + idx : ""),
                recipient -> reviewSendItem(g, idx, coin, recipient));
    }

    /** The reason this coin cannot be sent right now, or null when it can. */
    private String sendGuard(Group g, JSONObject coin) {
        String coinid = Nft.str(coin, "coinid");
        if (isPending(coinid)) {
            return "This item is already being sent — waiting for the chain to confirm";
        }
        if (Nft.isUnstampedLocked(g.meta, coin)) {
            return "Unstamped locked edition — the creator could reclaim it from the recipient. "
                    + "Stamp it in NFT wallet first";
        }
        if (!coinStillOurs(coinid)) {
            return "This coin is no longer in the wallet — pull to refresh";
        }
        return null;
    }

    /** Re-check the LIVE coin list by coinid — a dialog must never sell a coin we no longer hold. */
    private boolean coinStillOurs(String zCoinId) {
        org.minima.utils.json.JSONArray all = act.coins();
        if (all == null) return false;
        for (Object o : all) {
            if (Nft.str((JSONObject) o, "coinid").equals(zCoinId)) return true;
        }
        return false;
    }

    private interface RecipientCb { void onRecipient(String zAddr); }

    private void promptRecipient(String zTitle, final RecipientCb zCb) {
        final EditText addr = new EditText(act);
        addr.setHint("Recipient address (Mx… or 0x…)");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setTextColor(Design.text());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(addr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(zTitle)
                .setView(wrap)
                .setNeutralButton("Scan QR", (d, w) -> act.scanQr(s -> zCb.onRecipient(s == null ? "" : s.trim())))
                .setPositiveButton("Review", (d, w) -> zCb.onRecipient(addr.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "" when not ours, else a review-dialog notice naming the receiving key. */
    private String selfSendNotice(String zRecipient) {
        try {
            String r0x = TxnFactory.parseAddress(zRecipient).to0xString();
            Integer ki = act.keyIndexForAddress(r0x);
            if (ki != null) return "\nNote: recipient is THIS wallet's own address (key " + ki + ").";
        } catch (Exception ignore) { }
        return "";
    }

    private void reviewSendItem(final Group g, final int idx, final JSONObject coin, final String recipient) {
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid recipient address");
            String guard = sendGuard(g, coin);
            if (guard != null) throw new IllegalStateException(guard);
            Integer ki = act.keyIndexForAddress(Nft.str(coin, "address"));
            if (ki == null) throw new IllegalStateException("Coin address is not in our wallet: " + Nft.str(coin, "address"));
            final int keyIndex = ki;
            final String coinid = Nft.str(coin, "coinid");

            //Resolve the input BYTE-EXACT from the node (coinexport) before anything signs — the
            //coins-JSON token descriptor is lossy and consensus would reject the txn silently.
            List<org.minima.utils.json.JSONObject> one = new ArrayList<>();
            one.add(coin);
            act.resolveInputs(one, new MainActivity.InputsCb() {
                @Override public void onResolved(List<TxnFactory.InputCoin> inputs) {
                    final TxnFactory.InputCoin in = inputs.get(0);
                    String review = "NFT: " + g.meta.name + (idx >= 0 ? " #" + idx : "") + "\n"
                            + (g.meta.stateNft ? "StateNFT — state replayed, sent whole\n" : "Sent whole (indivisible)\n")
                            + "To: " + recipient + "\n"
                            + "coinid: " + coinid + "\n"
                            + selfSendNotice(recipient);
                    act.confirmSignAndPublish(review, keyIndex,
                            () -> act.factory().buildNftTransfer(in, recipient, Util.newTxnId()),
                            () -> {
                                PENDING.put(coinid, System.currentTimeMillis());
                                // The open dialogs now describe coins that changed — close rather than go stale.
                                if (mViewer != null) mViewer.dismiss();
                                if (mBrowser != null) mBrowser.dismiss();
                                refresh();
                            });
                }
                @Override public void onError(String message) {
                    Toast.makeText(act, "Could not prepare the coin: " + message, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // =============================================================================================
    // Send the whole collection — N sequential transactions with progress
    // =============================================================================================

    private void promptSendCollection(final Group g) {
        final List<JSONObject> sendable = new ArrayList<>();
        int pending = 0, unstamped = 0;
        for (JSONObject c : g.coins) {
            if (isPending(Nft.str(c, "coinid"))) { pending++; continue; }
            if (Nft.isUnstampedLocked(g.meta, c)) { unstamped++; continue; }
            sendable.add(c);
        }
        if (sendable.isEmpty()) {
            Toast.makeText(act, "Nothing sendable: " + pending + " already sending, "
                    + unstamped + " unstamped", Toast.LENGTH_LONG).show();
            return;
        }
        final int fpending = pending, funstamped = unstamped;
        promptRecipient("Send ALL of " + g.meta.name + " (" + sendable.size() + " items)",
                recipient -> reviewSendCollection(g, sendable, fpending, funstamped, recipient));
    }

    private void reviewSendCollection(final Group g, final List<JSONObject> coins,
                                      int zSkippedPending, int zSkippedUnstamped, final String recipient) {
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid recipient address");

            StringBuilder idxs = new StringBuilder();
            List<Integer> keys = new ArrayList<>();
            for (JSONObject c : coins) {
                Integer ki = act.keyIndexForAddress(Nft.str(c, "address"));
                if (ki == null) throw new IllegalStateException("Coin address is not in our wallet: " + Nft.str(c, "address"));
                int idx = Nft.itemIndex(c);
                idxs.append(idxs.length() == 0 ? "" : ", ").append(idx >= 0 ? "#" + idx : "item");
                if (!keys.contains(ki)) keys.add(ki);
            }
            StringBuilder usage = new StringBuilder();
            for (int ki : keys) {
                usage.append("\nKey ").append(ki).append(": next signature #")
                     .append(act.keyUses().currentUses(ki)).append(" of ").append(Util.WOTS_MAX_USES);
            }
            StringBuilder skipped = new StringBuilder();
            if (zSkippedPending > 0) skipped.append("\nSkipping ").append(zSkippedPending).append(" already-sending item(s).");
            if (zSkippedUnstamped > 0) skipped.append("\nSkipping ").append(zSkippedUnstamped)
                    .append(" UNSTAMPED item(s) — the creator could reclaim them; stamp in NFT wallet first.");

            String full = "Collection: " + g.meta.name + "\n"
                    + coins.size() + " items (" + idxs + ")\n"
                    + "To: " + recipient + "\n"
                    + selfSendNotice(recipient)
                    + skipped
                    + "\n\n" + coins.size() + " SEPARATE transactions will be signed and broadcast, one per item"
                    + usage
                    + "\n\nThis is IRREVERSIBLE. Once broadcast it cannot be undone.";

            new androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Confirm — review carefully")
                    .setMessage(full)
                    .setPositiveButton("Sign & broadcast all", (d, w) -> runCollectionSend(g, coins, recipient))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Sequentially: build (signs, keyuses advanced + snapshot resynced) → publish → next. */
    private void runCollectionSend(final Group g, final List<JSONObject> coins, final String recipient) {
        try {
            act.vault().assertSigningAllowed();
        } catch (SeedVault.SigningNotAllowedException block) {
            Toast.makeText(act, block.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        final TextView progress = new TextView(act);
        progress.setTextColor(Design.text());
        progress.setTextSize(14f);
        progress.setPadding(dp(24), dp(20), dp(24), dp(8));
        final boolean[] cancelled = {false};
        final androidx.appcompat.app.AlertDialog pd = new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Sending collection")
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton("Stop after current", (d, w) -> cancelled[0] = true)
                .create();
        pd.show();

        sendNext(g, coins, recipient, 0, new ArrayList<>(), progress, pd, cancelled);
    }

    private void sendNext(final Group g, final List<JSONObject> coins,
                          final String recipient, final int i, final List<String> txpowids,
                          final TextView progress, final androidx.appcompat.app.AlertDialog pd,
                          final boolean[] cancelled) {
        if (i >= coins.size() || cancelled[0]) {
            finishCollectionSend(coins.size(), i, txpowids, null, pd);
            return;
        }
        final JSONObject coin = coins.get(i);
        final int idx = Nft.itemIndex(coin);
        final String coinid = Nft.str(coin, "coinid");
        progress.setText("Item " + (idx >= 0 ? "#" + idx : "") + "  (" + (i + 1) + " of " + coins.size() + ")\n"
                + "Signing and broadcasting…");

        //Resolve this item's input BYTE-EXACT (coinexport) before signing.
        List<org.minima.utils.json.JSONObject> one = new ArrayList<>();
        one.add(coin);
        act.resolveInputs(one, new MainActivity.InputsCb() {
            @Override public void onResolved(List<TxnFactory.InputCoin> inputs) {
                final TxnFactory.BuiltTxn built;
                try {
                    try {
                        built = act.factory().buildNftTransfer(inputs.get(0), recipient, Util.newTxnId());
                    } finally {
                        // Same contract as SendView: the snapshot must never lag a (partial) sign.
                        try { act.vault().syncKeyUses(); }
                        catch (Exception snapEx) {
                            android.util.Log.w("wallet", "keyuses snapshot resync failed (safe to lag)", snapEx);
                        }
                    }
                } catch (Exception e) {
                    finishCollectionSend(coins.size(), i, txpowids,
                            "item " + (idx >= 0 ? "#" + idx : i + 1) + ": " + e.getMessage(), pd);
                    return;
                }
                publishOne(g, coins, recipient, i, txpowids, progress, pd, cancelled, coinid, idx, built);
            }
            @Override public void onError(String message) {
                finishCollectionSend(coins.size(), i, txpowids,
                        "item " + (idx >= 0 ? "#" + idx : i + 1) + " could not be prepared: " + message, pd);
            }
        });
    }

    private void publishOne(final Group g, final List<JSONObject> coins, final String recipient,
                            final int i, final List<String> txpowids, final TextView progress,
                            final androidx.appcompat.app.AlertDialog pd, final boolean[] cancelled,
                            final String coinid, final int idx, final TxnFactory.BuiltTxn built) {
        act.node().publish(built, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                PENDING.put(coinid, System.currentTimeMillis());
                txpowids.add(Util.extractTxpowid(json, built.getID()));
                sendNext(g, coins, recipient, i + 1, txpowids, progress, pd, cancelled);
            }
            @Override public void onError(String message) {
                finishCollectionSend(coins.size(), i, txpowids,
                        "item " + (idx >= 0 ? "#" + idx : i + 1) + " broadcast failed: " + message
                        + " (its signature is consumed — that leaf is safely skipped)", pd);
            }
        });
    }

    private void finishCollectionSend(int total, int attempted, List<String> txpowids,
                                      String error, androidx.appcompat.app.AlertDialog pd) {
        pd.dismiss();
        StringBuilder sb = new StringBuilder();
        sb.append("Sent ").append(txpowids.size()).append(" of ").append(total).append(" item(s)\n");
        if (error != null) sb.append("\nStopped: ").append(error)
                .append("\nRemaining items were NOT signed and stay in this wallet.\n");
        else if (attempted < total) sb.append("\nStopped by you — remaining items stay in this wallet.\n");
        if (!txpowids.isEmpty()) {
            sb.append("\ntxpowids:\n");
            for (String id : txpowids) sb.append(id).append("\n");
        }
        TextView msg = new TextView(act);
        msg.setText(sb.toString());
        msg.setTextIsSelectable(true);
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextColor(Design.text());
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(error == null ? "Collection send" : "Collection send stopped")
                .setView(wrap)
                .setPositiveButton("OK", null)
                .show();
        if (mViewer != null) mViewer.dismiss();
        if (mBrowser != null) mBrowser.dismiss();
        refresh();
        act.reload();
    }
}
