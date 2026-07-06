package org.minimarex.wallet;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONObject;
import org.minimarex.wallet.comms.NodeApi;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Send / Split / Consolidate tab — builds a locally-signed transaction with {@link TxnFactory} and
 * publishes it through the non-admin {@link org.minimarex.wallet.comms.NodeLink}.
 *
 * <h3>Ordering (why we do NOT build before the review)</h3>
 * Building a transaction SIGNS it, which consumes a one-time WOTS leaf and advances the keyuses counter.
 * So the review dialog is computed from a dry-run (coin selection + amount/change math only) and shows
 * the signature number that WILL be used ({@code keyuses.currentUses(0)}); only on the user's explicit
 * confirm do we (1) re-check the fail-safe gate, (2) actually build+sign, (3) snapshot keyuses into the
 * vault, then (4) publish. A wrong-then-cancelled review therefore never burns a leaf.
 *
 * <h3>Units</h3>
 * Native Minima ({@code 0x00}) display == raw. For a custom token the user types the DISPLAY amount and
 * we convert to raw via {@code Token.getScaledMinimaAmount} before selection/build (the factory itself
 * does no scaling). Token burns are unsupported in v1 (burn forced 0 for tokens).
 */
public class SendView extends BaseView {

    private enum Mode { SEND, SPLIT, CONSOLIDATE }

    private final LinearLayout container;
    private Mode mMode = Mode.SEND;
    private String mTokenId = Util.MINIMA_TOKENID;   // selected token

    // live field refs (rebuilt per mode)
    private EditText mRecipient, mAmount, mBurn, mSplitN, mConsolidateCount;

    public SendView(MainActivity a) {
        super(a, makeRoot(a));
        ScrollView sv = (ScrollView) root;
        container = (LinearLayout) sv.getChildAt(0);
        refresh();
    }

    private static View makeRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 24));
        sv.addView(col, new ScrollView.LayoutParams(MP(), WC()));
        return sv;
    }

    @Override
    public void onShown() { refresh(); }

    @Override
    public void refresh() {
        container.removeAllViews();

        // Fail-safe banner: if keyuses is untrusted, block and point to Settings.
        if (!act.vault().isKeyUsesTrusted()) {
            container.addView(blockedBanner());
            return;
        }

        container.addView(modeBar());
        container.addView(tokenPicker());

        switch (mMode) {
            case SEND:        buildSendForm();        break;
            case SPLIT:       buildSplitForm();       break;
            case CONSOLIDATE: buildConsolidateForm(); break;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mode + token pickers
    // ---------------------------------------------------------------------------------------------

    private View modeBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(modeChip("Send", Mode.SEND));
        bar.addView(modeChip("Split", Mode.SPLIT));
        bar.addView(modeChip("Consolidate", Mode.CONSOLIDATE));
        return bar;
    }

    private View modeChip(String label, Mode m) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(act, 10), 0, dp(act, 10));
        t.setTextSize(13f);
        boolean sel = mMode == m;
        t.setTextColor(sel ? Design.onAccent() : Design.dim());
        t.setBackgroundColor(sel ? Design.accent() : Design.surface());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WC(), 1f);
        lp.rightMargin = dp(act, 2);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { mMode = m; refresh(); });
        return t;
    }

    private View tokenPicker() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(act, 14), 0, dp(act, 4));
        col.addView(label("TOKEN"));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TokenBalance> bals = act.balances();
        if (bals.isEmpty()) {
            col.addView(hint("No coins yet — fund your address on the Receive tab."));
        }
        for (TokenBalance b : bals) {
            row.addView(tokenChip(b));
        }
        ScrollView.LayoutParams lp = new ScrollView.LayoutParams(MP(), WC());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.addView(row);
        col.addView(hs, lp);
        return col;
    }

    private View tokenChip(TokenBalance b) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 8));
        boolean sel = b.tokenid.equals(mTokenId) || (b.isMinima() && Util.isMinima(mTokenId));
        chip.setBackgroundColor(sel ? Design.accentSoft() : Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC(), WC());
        lp.rightMargin = dp(act, 6);
        chip.setLayoutParams(lp);
        TextView name = new TextView(act);
        name.setText(b.isMinima() ? "MINIMA" : (b.meta != null && b.meta.ticker != null && !b.meta.ticker.isEmpty() ? b.meta.ticker : b.name));
        name.setTextColor(sel ? Design.accent() : Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(12f);
        TextView amt = new TextView(act);
        amt.setText(Util.tidyAmount(b.sendable));
        amt.setTextColor(Design.dim());
        amt.setTextSize(11f);
        chip.addView(name);
        chip.addView(amt);
        chip.setOnClickListener(v -> { mTokenId = b.tokenid; refresh(); });
        return chip;
    }

    // ---------------------------------------------------------------------------------------------
    // Forms
    // ---------------------------------------------------------------------------------------------

    private void buildSendForm() {
        container.addView(label("RECIPIENT (Mx… or 0x…)"));
        mRecipient = field(InputType.TYPE_CLASS_TEXT);
        container.addView(mRecipient);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(smallButton("Paste", v -> paste(mRecipient)));
        btns.addView(smallButton("Scan QR", v -> act.scanQr(text -> {
            if (text != null) mRecipient.setText(text.trim());
        })));
        container.addView(btns);

        // Raw 0x addresses carry no checksum → no typo protection. Warn prominently when one is entered.
        final TextView rawWarn = rawAddrWarning();
        container.addView(rawWarn);
        mRecipient.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                boolean raw = Util.isRaw0xAddress(e.toString().trim());
                rawWarn.setVisibility(raw ? View.VISIBLE : View.GONE);
            }
        });

        container.addView(label("AMOUNT"));
        mAmount = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(mAmount);
        container.addView(smallButton("MAX", v -> mAmount.setText(maxDisplay())));

        if (isMinima()) {
            container.addView(label("BURN (optional)"));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }

        container.addView(reviewButton("Review send", this::reviewSend));
    }

    private void buildSplitForm() {
        container.addView(hint("Split coins of the selected token into N equal self-coins (plus change)."));
        container.addView(label("AMOUNT TO SPLIT"));
        mAmount = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(mAmount);
        container.addView(smallButton("MAX", v -> mAmount.setText(maxDisplay())));

        container.addView(label("NUMBER OF COINS (1.." + TxnFactory.MAX_SPLIT_COINS + ")"));
        mSplitN = field(InputType.TYPE_CLASS_NUMBER);
        mSplitN.setText("2");
        container.addView(mSplitN);

        if (isMinima()) {
            container.addView(label("BURN (optional)"));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }
        container.addView(reviewButton("Review split", this::reviewSplit));
    }

    private void buildConsolidateForm() {
        int available = coinsOfToken().size();
        container.addView(hint("Merge many small coins of the selected token into one. Available coins: "
                + available + ". Picks the smallest first."));
        container.addView(label("HOW MANY COINS (3.." + CoinSelector.MAX_INPUTS + ")"));
        mConsolidateCount = field(InputType.TYPE_CLASS_NUMBER);
        mConsolidateCount.setText(String.valueOf(Math.min(Math.max(available, 3), CoinSelector.MAX_INPUTS)));
        container.addView(mConsolidateCount);

        if (isMinima()) {
            container.addView(label("BURN (optional)"));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }
        container.addView(reviewButton("Review consolidate", this::reviewConsolidate));
    }

    // ---------------------------------------------------------------------------------------------
    // Review (dry run) → confirm → build+sign → publish
    // ---------------------------------------------------------------------------------------------

    private void reviewSend() {
        try {
            String recipient = mRecipient.getText().toString().trim();
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid recipient address");

            MiniNumber burn = parseBurn();
            Token token = tokenDescriptor();
            MiniNumber amountRaw = toRaw(mAmount.getText().toString(), token);
            MiniNumber required = amountRaw.add(isMinima() ? burn : MiniNumber.ZERO);

            List<JSONObject> sel = selectToCover(required);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber change = inSum.sub(required);

            StringBuilder rev = new StringBuilder();
            rev.append("Token: ").append(tokenLabel()).append("\n");
            rev.append("To: ").append(Util.shorten(recipient)).append("\n");
            rev.append("Amount: ").append(mAmount.getText()).append("\n");
            rev.append("Inputs: ").append(sel.size()).append(" coin(s), raw ").append(inSum).append("\n");
            if (change.isMore(MiniNumber.ZERO)) rev.append("Change: ").append(change).append(" (to you)\n");
            if (isMinima()) rev.append("Burn: ").append(burn).append("\n");

            confirmAndSend(rev.toString(), () ->
                    act.factory().buildSend(toInputs(sel), recipient, amountRaw, tokenIdData(), burn, newId()));
        } catch (Exception e) {
            err(e);
        }
    }

    private void reviewSplit() {
        try {
            int n = Integer.parseInt(mSplitN.getText().toString().trim());
            if (n < 1 || n > TxnFactory.MAX_SPLIT_COINS) throw new IllegalArgumentException("N must be 1.." + TxnFactory.MAX_SPLIT_COINS);
            MiniNumber burn = parseBurn();
            Token token = tokenDescriptor();
            MiniNumber amountRaw = toRaw(mAmount.getText().toString(), token);
            MiniNumber required = amountRaw.add(isMinima() ? burn : MiniNumber.ZERO);

            List<JSONObject> sel = selectToCover(required);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber change = inSum.sub(required);

            String rev = "Token: " + tokenLabel() + "\n"
                    + "Split " + mAmount.getText() + " into " + n + " coins (to yourself)\n"
                    + "Inputs: " + sel.size() + " coin(s), raw " + inSum + "\n"
                    + (change.isMore(MiniNumber.ZERO) ? "Change: " + change + "\n" : "")
                    + (isMinima() ? "Burn: " + burn + "\n" : "");

            final int fn = n;
            confirmAndSend(rev, () ->
                    act.factory().buildSplit(toInputs(sel), amountRaw, fn, tokenIdData(), burn, newId()));
        } catch (Exception e) {
            err(e);
        }
    }

    private void reviewConsolidate() {
        try {
            int count = Integer.parseInt(mConsolidateCount.getText().toString().trim());
            if (count < 3) throw new IllegalArgumentException("Consolidate needs at least 3 coins");
            MiniNumber burn = parseBurn();

            List<JSONObject> sel = CoinSelector.selectSmallest(act.coins(), mTokenId, count);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber out = isMinima() ? inSum.sub(burn) : inSum;

            String rev = "Token: " + tokenLabel() + "\n"
                    + "Merge " + sel.size() + " coins (raw " + inSum + ") into 1 (to yourself)\n"
                    + "Output: " + out + "\n"
                    + (isMinima() ? "Burn: " + burn + "\n" : "");

            confirmAndSend(rev, () ->
                    act.factory().buildConsolidate(toInputs(sel), tokenIdData(), burn, newId()));
        } catch (Exception e) {
            err(e);
        }
    }

    /** Show the review dialog, and on confirm run the fail-safe gate, build+sign, snapshot, publish. */
    private void confirmAndSend(String review, java.util.concurrent.Callable<TxnFactory.BuiltTxn> builder) {
        int nextLeaf = act.keyUses().currentUses(0);
        String full = review
                + "\nUsing signature #" + nextLeaf + " of " + Util.WOTS_MAX_USES
                + "\n\nThis is IRREVERSIBLE. Once broadcast it cannot be undone.";

        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Confirm — review carefully")
                .setMessage(full)
                .setPositiveButton("Sign & broadcast", (d, w) -> {
                    try {
                        // Fail-safe gate — refuse to sign when keyuses is untrusted. Throws BEFORE any
                        // counter change, so no snapshot is needed on this path.
                        act.vault().assertSigningAllowed();

                        TxnFactory.BuiltTxn built;
                        try {
                            // Build = sign (advances the live keyuses counter, crash-safe).
                            built = builder.call();
                        } finally {
                            // ALWAYS resync the portable snapshot to the live counter, even if build/sign
                            // threw partway after reserving a leaf — the exportable snapshot must never lag
                            // a completed (or partially completed) sign. Its OWN try/catch so a snapshot-
                            // persist failure can never mask the build error or skip publish: the live
                            // counter is already durably advanced by reserveNextUse, so the snapshot only
                            // lags (safe under MAX-on-read on the next open).
                            try { act.vault().syncKeyUses(); }
                            catch (Exception snapEx) {
                                android.util.Log.w("wallet", "keyuses snapshot resync failed (safe to lag)", snapEx);
                            }
                        }

                        publish(built);
                    } catch (SeedVault.SigningNotAllowedException block) {
                        Toast.makeText(act, block.getMessage(), Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        err(e);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void publish(TxnFactory.BuiltTxn built) {
        Toast.makeText(act, "Broadcasting…", Toast.LENGTH_SHORT).show();
        act.node().publish(built, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                String txpowid = Util.extractTxpowid(json, built.getID());
                Toast.makeText(act, "Sent. txpowid " + Util.shorten(txpowid), Toast.LENGTH_LONG).show();
                act.reload();
            }
            @Override public void onError(String message) {
                Toast.makeText(act, "Broadcast failed: " + message
                        + "\n(Signature already consumed — that leaf is safely skipped.)", Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Coin / token helpers
    // ---------------------------------------------------------------------------------------------

    private boolean isMinima() { return Util.isMinima(mTokenId); }

    private MiniData tokenIdData() { return new MiniData(isMinima() ? "0x00" : mTokenId); }

    private String tokenLabel() {
        for (TokenBalance b : act.balances()) {
            if (b.tokenid.equals(mTokenId)) return b.isMinima() ? "Minima" : b.name;
        }
        return isMinima() ? "Minima" : mTokenId;
    }

    /** All raw coins of the selected token from the last node load. */
    private List<JSONObject> coinsOfToken() {
        List<JSONObject> out = new ArrayList<>();
        org.minima.utils.json.JSONArray all = act.coins();
        if (all == null) return out;
        for (Object o : all) {
            JSONObject c = (JSONObject) o;
            String tid = String.valueOf(c.get("tokenid"));
            if (isMinima() ? Util.isMinima(tid) : mTokenId.equals(tid)) out.add(c);
        }
        return out;
    }

    private List<JSONObject> selectToCover(MiniNumber requiredRaw) {
        return CoinSelector.selectToCover(act.coins(), mTokenId, requiredRaw);
    }

    private MiniNumber sumRaw(List<JSONObject> zSel) { return CoinSelector.sumRaw(zSel); }

    /** Map selected bundled-JSON coins → factory InputCoins (all at our primary key index 0). */
    private List<TxnFactory.InputCoin> toInputs(List<JSONObject> zSel) {
        List<TxnFactory.InputCoin> in = new ArrayList<>();
        for (JSONObject c : zSel) in.add(TxnFactory.fromCoinJson(c, 0));
        return in;
    }

    /** The Token descriptor for the selected custom token (null for native Minima). */
    private Token tokenDescriptor() {
        if (isMinima()) return null;
        List<JSONObject> pool = coinsOfToken();
        if (pool.isEmpty()) throw new IllegalStateException("No coins of this token");
        return TxnFactory.fromCoinJson(pool.get(0), 0).getToken();
    }

    /** Convert a human display amount to RAW units (native == display; token scaled down). */
    private MiniNumber toRaw(String zDisplay, Token zToken) {
        MiniNumber disp = new MiniNumber(zDisplay.trim());
        if (disp.isLessEqual(MiniNumber.ZERO)) throw new IllegalArgumentException("Amount must be > 0");
        if (isMinima() || zToken == null) return disp;
        return zToken.getScaledMinimaAmount(disp);
    }

    private MiniNumber parseBurn() {
        if (!isMinima() || mBurn == null) return MiniNumber.ZERO;
        String s = mBurn.getText().toString().trim();
        if (s.isEmpty()) return MiniNumber.ZERO;
        MiniNumber b = new MiniNumber(s);
        if (b.isLess(MiniNumber.ZERO)) throw new IllegalArgumentException("Burn cannot be negative");
        return b;
    }

    /**
     * The MAX display amount = the selected token's FULL sendable balance (no burn headroom is
     * subtracted). For native Minima a non-zero burn added ON TOP of MAX would exceed the balance and
     * the coin selection would then report "insufficient" at review — so to send-all WITH a burn, lower
     * the amount by the burn. (Keeping MAX at the full sendable is intentional: most sends use burn=0.)
     */
    private String maxDisplay() {
        for (TokenBalance b : act.balances()) {
            if (b.tokenid.equals(mTokenId)) return Util.tidyAmount(b.sendable);
        }
        return "0";
    }

    private String newId() {
        byte[] r = new byte[8];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder("mw");
        for (byte x : r) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Small view builders
    // ---------------------------------------------------------------------------------------------

    private View blockedBanner() {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.redSoft());
        b.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        TextView t = new TextView(act);
        t.setText("Sending is blocked");
        t.setTextColor(Design.red());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(16f);
        TextView d = new TextView(act);
        d.setText("This seed has no trusted signatures-used history, so signing could reuse a one-time key "
                + "and lose funds. Open Settings → Security to confirm the seed is brand-new or restore a "
                + "keyuses backup.");
        d.setTextColor(Design.text());
        d.setTextSize(13f);
        d.setPadding(0, dp(act, 8), 0, dp(act, 12));
        Button go = new Button(act);
        go.setText("Open Settings");
        go.setAllCaps(false);
        go.setTextColor(Design.onAccent());
        go.setBackgroundColor(Design.accent());
        go.setOnClickListener(v -> act.goToTab(MainActivity.TAB_SETTINGS));
        b.addView(t); b.addView(d); b.addView(go);
        return b;
    }

    private TextView label(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(act, 14), 0, dp(act, 4));
        return t;
    }

    private TextView hint(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(Design.dim2()); t.setTextSize(12f);
        t.setPadding(0, dp(act, 6), 0, dp(act, 4));
        return t;
    }

    /** Inline (initially hidden) warning shown when the recipient is a raw, checksum-less 0x address. */
    private TextView rawAddrWarning() {
        TextView t = new TextView(act);
        t.setText("Raw 0x address — no typo protection. A single wrong character sends to a different, "
                + "unrecoverable address. Prefer an Mx address or a scanned QR code.");
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setBackgroundColor(Design.redSoft());
        t.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        lp.topMargin = dp(act, 8);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        return t;
    }

    private EditText field(int inputType) {
        EditText e = new EditText(act);
        e.setInputType(inputType);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        e.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        e.setLayoutParams(lp);
        return e;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Design.accent());
        b.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC(), WC());
        lp.topMargin = dp(act, 6); lp.rightMargin = dp(act, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button reviewButton(String label, Runnable r) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Design.onAccent());
        b.setBackgroundColor(Design.accent());
        b.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        lp.topMargin = dp(act, 20);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void paste(EditText into) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            if (t != null) into.setText(t.toString().trim());
        }
    }

    private void err(Exception e) {
        Toast.makeText(act, e.getMessage() == null ? e.toString() : e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private static int MP() { return LinearLayout.LayoutParams.MATCH_PARENT; }
    private static int WC() { return LinearLayout.LayoutParams.WRAP_CONTENT; }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
