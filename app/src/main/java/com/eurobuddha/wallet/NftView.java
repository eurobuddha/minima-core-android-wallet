package com.eurobuddha.wallet;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.minima.utils.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * NFTs tab: a real gallery over the NFT / StateNFT coins held at this wallet's 64 base addresses.
 *
 * <pre>
 *   Gallery (2-col artwork grid of collections)
 *     → Collection browser (full-screen: description, item grid, Send collection)
 *       → Item viewer (full-screen, pinch-zoom full resolution)
 *         → Details (every field + full ids, copyable) / Send item
 * </pre>
 *
 * Display and SEND only — never mint. Sends go through the local signer
 * ({@link TxnFactory#buildNftTransfer}): each coin spends whole with its state replayed. A
 * StateNFT's sealed state must equal the transaction state, so a collection send is N separate
 * transactions, signed and broadcast sequentially with progress (same reason the NFT wallet
 * transfers collections one coin at a time).
 */
public class NftView extends BaseView {

    private final LinearLayout grid;
    private final TextView status;

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

    // =============================================================================================
    // Gallery (tab content)
    // =============================================================================================

    @Override
    public void refresh() {
        grid.removeAllViews();

        org.minima.utils.json.JSONArray all = act.coins();
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        if (all != null) {
            for (Object o : all) {
                JSONObject c = (JSONObject) o;
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
        for (Group g : groups.values()) {
            java.util.Collections.sort(g.coins, (a, b) -> Nft.itemIndex(a) - Nft.itemIndex(b));
        }

        if (groups.isEmpty()) {
            status.setText(act.isPaired()
                    ? "No NFTs at this wallet's addresses"
                    : "Pair with Minima Core to load your NFTs");
            return;
        }
        status.setText(groups.size() + " collection(s) · tap artwork to browse · display & send only");

        // 2-column artwork grid.
        List<Group> list = new ArrayList<>(groups.values());
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout rowv = new LinearLayout(act);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            rowv.addView(galleryTile(list.get(i)),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            View right = (i + 1 < list.size()) ? galleryTile(list.get(i + 1)) : new View(act);
            rowv.addView(right,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            grid.addView(rowv);
        }
    }

    private View galleryTile(final Group g) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(8));

        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        art.setImageBitmap(Identicon.forToken(g.tokenid, dp(160)));
        JSONObject cover = g.coins.get(0);
        loadArt(g, cover, art, false);
        cell.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(act);
        name.setText(g.meta.name);
        name.setTextColor(Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(14f);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        cell.addView(name);

        TextView sub = new TextView(act);
        sub.setText((g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size() + " item(s)");
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

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Design.bg());
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(14), dp(12), dp(16));

        // Header: back · title/count · Send collection
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(act);
        back.setText("←");
        back.setTextSize(24f);
        back.setTextColor(Design.text());
        back.setPadding(dp(4), 0, dp(14), 0);
        back.setOnClickListener(v -> d.dismiss());
        header.addView(back);
        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(act);
        title.setText(g.meta.name);
        title.setTextColor(Design.text());
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(18f);
        titleCol.addView(title);
        TextView count = new TextView(act);
        count.setText((g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size() + " item(s) held here");
        count.setTextColor(Design.dim());
        count.setTextSize(11f);
        titleCol.addView(count);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button sendAll = new Button(act);
        sendAll.setText("Send collection");
        sendAll.setTextSize(12f);
        sendAll.setTextColor(Design.onAccent());
        sendAll.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        sendAll.setOnClickListener(v -> promptSendCollection(g, d));
        header.addView(sendAll);
        content.addView(header);

        if (!g.meta.description.isEmpty()) {
            TextView desc = new TextView(act);
            desc.setText(g.meta.description);
            desc.setTextColor(Design.text());
            desc.setTextSize(13f);
            desc.setPadding(dp(4), dp(10), dp(4), dp(4));
            content.addView(desc);
        }

        // 2-column item grid.
        for (int i = 0; i < g.coins.size(); i += 2) {
            LinearLayout rowv = new LinearLayout(act);
            rowv.setOrientation(LinearLayout.HORIZONTAL);
            rowv.addView(itemTile(g, g.coins.get(i)),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            View right = (i + 1 < g.coins.size()) ? itemTile(g, g.coins.get(i + 1)) : new View(act);
            rowv.addView(right,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(rowv);
        }

        sv.addView(content);
        d.setContentView(sv);
        d.show();
    }

    private View itemTile(final Group g, final JSONObject coin) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(8));

        SquareImageView art = new SquareImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Design.surface());
        int idx = Nft.itemIndex(coin);
        art.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(160)));
        loadArt(g, coin, art, false);
        cell.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(act);
        label.setText(idx >= 0 ? "#" + idx : "item");
        label.setTextColor(Design.text());
        label.setTextSize(13f);
        label.setPadding(dp(2), dp(4), dp(2), 0);
        cell.addView(label);

        cell.setOnClickListener(v -> openViewer(g, coin));
        return cell;
    }

    // =============================================================================================
    // Item viewer (full screen, pinch-zoom, full resolution)
    // =============================================================================================

    private void openViewer(final Group g, final JSONObject coin) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final int idx = Nft.itemIndex(coin);

        FrameLayout frame = new FrameLayout(act);
        frame.setBackgroundColor(Color.BLACK);

        ZoomImageView zoom = new ZoomImageView(act);
        zoom.setImageBitmap(Identicon.forToken(g.tokenid + idx, dp(320)));
        loadArt(g, coin, zoom, true);   // full resolution (bounded 1600px)
        frame.addView(zoom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar: back + title.
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView back = new TextView(act);
        back.setText("←");
        back.setTextSize(24f);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(4), 0, dp(14), 0);
        back.setOnClickListener(v -> d.dismiss());
        top.addView(back);
        TextView title = new TextView(act);
        title.setText(g.meta.name + (idx >= 0 ? "  #" + idx : ""));
        title.setTextColor(Color.WHITE);
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(16f);
        top.addView(title);
        frame.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bottom bar: Details · Send.
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(6));
        Button details = new Button(act);
        details.setText("Details");
        details.setTextColor(Color.WHITE);
        details.setBackgroundColor(Color.TRANSPARENT);
        details.setOnClickListener(v -> showDetails(g, coin));
        bottom.addView(details, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(act);
        send.setText("Send");
        send.setTextColor(Design.onAccent());
        send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        send.setOnClickListener(v -> promptSendItem(g, coin));
        bottom.addView(send, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        frame.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

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
                // Port 1 in embed mode is the sealed image payload — size, not a base64 dump.
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
        promptRecipient("Send " + g.meta.name + (idx >= 0 ? " #" + idx : ""),
                recipient -> reviewSendItem(g, idx, coin, recipient));
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

    private void reviewSendItem(final Group g, final int idx, final JSONObject coin, final String recipient) {
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid recipient address");
            Integer ki = act.keyIndexForAddress(Nft.str(coin, "address"));
            if (ki == null) throw new IllegalStateException("Coin address is not in our wallet: " + Nft.str(coin, "address"));
            final int keyIndex = ki;
            final TxnFactory.InputCoin in = TxnFactory.fromCoinJson(coin, keyIndex);

            String review = "NFT: " + g.meta.name + (idx >= 0 ? " #" + idx : "") + "\n"
                    + (g.meta.stateNft ? "StateNFT — state replayed, sent whole\n" : "Sent whole (indivisible)\n")
                    + "To: " + recipient + "\n"
                    + "coinid: " + Nft.str(coin, "coinid") + "\n";

            act.confirmSignAndPublish(review, keyIndex, () ->
                    act.factory().buildNftTransfer(in, recipient, Util.newTxnId()));
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // =============================================================================================
    // Send the whole collection — N sequential transactions with progress
    // =============================================================================================

    private void promptSendCollection(final Group g, final Dialog browser) {
        promptRecipient("Send ALL of " + g.meta.name + " (" + g.coins.size() + " items)",
                recipient -> reviewSendCollection(g, browser, recipient));
    }

    private void reviewSendCollection(final Group g, final Dialog browser, final String recipient) {
        try {
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid recipient address");

            final List<JSONObject> coins = new ArrayList<>(g.coins);
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

            String full = "Collection: " + g.meta.name + "\n"
                    + coins.size() + " items (" + idxs + ")\n"
                    + "To: " + recipient + "\n"
                    + "\n" + coins.size() + " SEPARATE transactions will be signed and broadcast, one per item"
                    + usage
                    + "\n\nThis is IRREVERSIBLE. Once broadcast it cannot be undone.";

            new androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Confirm — review carefully")
                    .setMessage(full)
                    .setPositiveButton("Sign & broadcast all", (d, w) -> runCollectionSend(g, browser, coins, recipient))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Sequentially: build (signs, keyuses advanced + snapshot resynced) → publish → next. */
    private void runCollectionSend(final Group g, final Dialog browser,
                                   final List<JSONObject> coins, final String recipient) {
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

        sendNext(g, browser, coins, recipient, 0, new ArrayList<>(), progress, pd, cancelled);
    }

    private void sendNext(final Group g, final Dialog browser, final List<JSONObject> coins,
                          final String recipient, final int i, final List<String> txpowids,
                          final TextView progress, final androidx.appcompat.app.AlertDialog pd,
                          final boolean[] cancelled) {
        if (i >= coins.size() || cancelled[0]) {
            finishCollectionSend(browser, coins.size(), i, txpowids, null, pd);
            return;
        }
        final JSONObject coin = coins.get(i);
        final int idx = Nft.itemIndex(coin);
        progress.setText("Item " + (idx >= 0 ? "#" + idx : "") + "  (" + (i + 1) + " of " + coins.size() + ")\n"
                + "Signing and broadcasting…");

        final TxnFactory.BuiltTxn built;
        try {
            Integer ki = act.keyIndexForAddress(Nft.str(coin, "address"));
            if (ki == null) throw new IllegalStateException("Coin address not ours: " + Nft.str(coin, "address"));
            TxnFactory.InputCoin in = TxnFactory.fromCoinJson(coin, ki);
            try {
                built = act.factory().buildNftTransfer(in, recipient, Util.newTxnId());
            } finally {
                // Same contract as SendView: the snapshot must never lag a (partial) sign.
                try { act.vault().syncKeyUses(); }
                catch (Exception snapEx) {
                    android.util.Log.w("wallet", "keyuses snapshot resync failed (safe to lag)", snapEx);
                }
            }
        } catch (Exception e) {
            finishCollectionSend(browser, coins.size(), i, txpowids,
                    "item " + (idx >= 0 ? "#" + idx : i) + ": " + e.getMessage(), pd);
            return;
        }

        act.node().publish(built, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                txpowids.add(Util.extractTxpowid(json, built.getID()));
                sendNext(g, browser, coins, recipient, i + 1, txpowids, progress, pd, cancelled);
            }
            @Override public void onError(String message) {
                finishCollectionSend(browser, coins.size(), i, txpowids,
                        "item " + (idx >= 0 ? "#" + idx : i) + " broadcast failed: " + message
                        + " (its signature is consumed — that leaf is safely skipped)", pd);
            }
        });
    }

    private void finishCollectionSend(Dialog browser, int total, int attempted,
                                      List<String> txpowids, String error, androidx.appcompat.app.AlertDialog pd) {
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
        if (browser != null) browser.dismiss();
        act.reload();
    }
}
