package com.eurobuddha.wallet;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.minima.utils.json.JSONObject;

/**
 * NFTs tab: display and SEND (never mint) the NFT / StateNFT coins held at this wallet's 64 base
 * addresses — the same gallery rules as the NFT wallet (non-Minima token, 0 decimals; StateNFT
 * items keyed by their sealed state-0 index with embedded or url artwork), but built purely from
 * {@code MainActivity.coins()} (already filtered to OUR addresses) and sent through the local
 * signer: {@link TxnFactory#buildNftTransfer} replays the coin's state and spends it whole.
 */
public class NftView extends BaseView {

    private final LinearLayout list;
    private final TextView status;

    public NftView(MainActivity a) {
        super(a, buildRoot(a));
        LinearLayout content = (LinearLayout) ((ScrollView) root).getChildAt(0);
        status = (TextView) content.getChildAt(0);
        list   = (LinearLayout) content.getChildAt(1);
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
        sv.addView(content);
        return sv;
    }

    private static int dp(MainActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return dp(act, v); }

    /** One owned NFT token: its meta and our coins of it. */
    private static class Group {
        Nft.Meta meta;
        final List<JSONObject> coins = new ArrayList<>();
    }

    @Override
    public void refresh() {
        list.removeAllViews();

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
                if (g == null) { g = new Group(); g.meta = meta; groups.put(tokenid, g); }
                g.coins.add(c);
            }
        }

        if (groups.isEmpty()) {
            status.setText(act.isPaired()
                    ? "No NFTs at this wallet's addresses"
                    : "Pair with Minima Core to load your NFTs");
            return;
        }
        status.setText(groups.size() + " NFT token(s) · display and send only — minting stays in NFT wallet");

        for (Map.Entry<String, Group> e : groups.entrySet()) {
            list.addView(card(e.getKey(), e.getValue()));
        }
    }

    private View card(final String tokenid, final Group g) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(Design.surface());
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        ImageView icon = new ImageView(act);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageBitmap(Identicon.forToken(tokenid, dp(56)));
        JSONObject firstCoin = g.coins.get(0);
        String iconUrl = Nft.imageUrl(g.meta, Nft.itemIndex(firstCoin), firstCoin);
        if (iconUrl != null && !iconUrl.isEmpty()) ImageLoader.loadOver(act, iconUrl, icon, null);
        card.addView(icon);

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(act);
        name.setText(g.meta.name);
        name.setTextColor(Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(15f);
        text.addView(name);
        TextView sub = new TextView(act);
        sub.setText((g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size() + " coin(s)");
        sub.setTextColor(Design.dim());
        sub.setTextSize(11f);
        text.addView(sub);
        card.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(v -> showDetail(tokenid, g));
        return card;
    }

    /** Collection detail: every owned coin with its artwork, index and a Send button. */
    private void showDetail(final String tokenid, final Group g) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(8));

        if (!g.meta.description.isEmpty()) {
            TextView desc = new TextView(act);
            desc.setText(g.meta.description);
            desc.setTextColor(Design.text());
            desc.setTextSize(13f);
            body.addView(desc);
        }
        TextView tid = new TextView(act);
        tid.setText("tokenid:\n" + tokenid);
        tid.setTextIsSelectable(true);
        tid.setTypeface(Typeface.MONOSPACE);
        tid.setTextColor(Design.dim());
        tid.setTextSize(11f);
        tid.setPadding(0, dp(8), 0, dp(8));
        body.addView(tid);

        for (final JSONObject coin : g.coins) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            int idx = Nft.itemIndex(coin);
            ImageView art = new ImageView(act);
            art.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(Identicon.forToken(tokenid + idx, dp(72)));
            String url = Nft.imageUrl(g.meta, idx, coin);
            if (url != null && !url.isEmpty()) ImageLoader.loadOver(act, url, art, null);
            row.addView(art);

            TextView label = new TextView(act);
            label.setText(idx >= 0 ? "#" + idx : "item");
            label.setTextColor(Design.text());
            label.setTextSize(13f);
            label.setPadding(dp(10), 0, dp(10), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button send = new Button(act);
            send.setText("Send");
            send.setTextColor(Design.onAccent());
            send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
            send.setOnClickListener(v -> promptSend(g, idx, coin));
            row.addView(send);

            body.addView(row);
        }

        ScrollView wrap = new ScrollView(act);
        wrap.addView(body);
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(g.meta.name)
                .setView(wrap)
                .setNegativeButton("Close", null)
                .show();
    }

    /** Ask for the recipient, then route through the shared review → gate → sign → publish flow. */
    private void promptSend(final Group g, final int idx, final JSONObject coin) {
        final EditText addr = new EditText(act);
        addr.setHint("Recipient address (Mx… or 0x…)");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setTextColor(Design.text());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(addr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Send " + g.meta.name + (idx >= 0 ? " #" + idx : ""))
                .setView(wrap)
                .setNeutralButton("Scan QR", (d, w) -> act.scanQr(scanned -> promptSendPrefilled(g, idx, coin, scanned)))
                .setPositiveButton("Review", (d, w) -> reviewSend(g, idx, coin, addr.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptSendPrefilled(final Group g, final int idx, final JSONObject coin, String zAddr) {
        reviewSend(g, idx, coin, zAddr == null ? "" : zAddr.trim());
    }

    private void reviewSend(final Group g, final int idx, final JSONObject coin, final String recipient) {
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
            android.widget.Toast.makeText(act, String.valueOf(e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
