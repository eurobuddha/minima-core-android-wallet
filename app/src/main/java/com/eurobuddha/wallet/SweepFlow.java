package com.eurobuddha.wallet;

import android.text.InputType;
import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * SweepFlow — move EVERYTHING this wallet holds (native Minima, every fungible token, every
 * NFT / StateNFT with its state intact) to one destination address, as one user action.
 *
 * <p>One action, MANY transactions: a StateNFT's locked script demands a per-coin transaction
 * (SAMESTATE + VERIFYOUT, state replayed verbatim — {@link TxnFactory#buildNftTransfer}), and a
 * TxPoW has a consensus size cap (64K, Magic.DEFAULT_TXPOW_SIZE) that a classic WOTS signature
 * chain (~8.4K each) plus the node-attached MMR proofs eats quickly, so pooled sends are chunked
 * by inputs AND by distinct signing keys. The whole batch runs sequentially with the proven
 * collection-send contract: build=sign (keyuses advanced first) → publish → next, screen held on
 * (Doze loses broadcast txns before block inclusion — proven live 17/19), then NOTHING is called
 * swept until the chain shows each coin's spend confirmed; stragglers get one-tap retry.
 *
 * <h3>What is deliberately skipped (and reported)</h3>
 * <ul>
 *   <li>Coins already confirming from an earlier send (double-send guard).</li>
 *   <li>UNSTAMPED locked-edition StateNFTs — the creator could reclaim them from the recipient.</li>
 *   <li>Coins at addresses this wallet does not hold the key for.</li>
 * </ul>
 */
public final class SweepFlow {

    // ponytail: conservative static chunking keeps every pooled txn under the 64K TxPoW cap
    // (4 sigs ≈ 34K + 10 inputs' node-attached proofs); measure real built sizes if sweeps
    // ever need fewer, bigger txns.
    static final int MAX_INPUTS_PER_TXN = 10;
    static final int MAX_KEYS_PER_TXN   = 4;

    /** The standard fungible token script — the only script safe to spend pooled. */
    static final String PLAIN_TOKEN_SCRIPT = "RETURN TRUE";

    // =============================================================================================
    // Plan — pure, unit-testable
    // =============================================================================================

    /** addr(0x) -> wallet key index, or null when the address is not ours. */
    interface KeyLookup { Integer keyIndexFor(String zAddr0x); }

    /** One transaction to build: either a pooled send or a single whole-coin transfer. */
    static final class Job {
        final boolean whole;            // true = per-coin NFT/state transfer, false = pooled send
        final String tokenid;           // pooled: the token being sent (0x00 = Minima)
        final List<JSONObject> coins;   // the input coins (JSON, from act.coins())
        final String label;             // for progress + report
        Job(boolean zWhole, String zTokenid, List<JSONObject> zCoins, String zLabel) {
            whole = zWhole; tokenid = zTokenid; coins = zCoins; label = zLabel;
        }
    }

    static final class Plan {
        final List<Job> jobs = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();      // human reasons, one per skipped coin
        final Map<String, String> totals = new LinkedHashMap<>(); // display label -> amount summary
        int signatures = 0;                                   // total WOTS leaves the plan consumes

        boolean isEmpty() { return jobs.isEmpty(); }

        /** Every coinid the plan will spend. */
        List<String> coinIds() {
            List<String> out = new ArrayList<>();
            for (Job j : jobs) for (JSONObject c : j.coins) out.add(Nft.str(c, "coinid"));
            return out;
        }
    }

    /**
     * Classify every owned coin into jobs. Per-coin whole transfers for anything carrying state
     * or a non-trivial token script (NFTs, StateNFTs, scripted tokens); pooled chunked sends for
     * plain fungible tokens and native Minima. Pure: no Android, no node.
     */
    static Plan plan(JSONArray zCoins, KeyLookup zKeys, Set<String> zPendingCoinIds) {
        Plan plan = new Plan();
        if (zCoins == null) return plan;

        List<JSONObject> minima = new ArrayList<>();
        Map<String, List<JSONObject>> fungible = new LinkedHashMap<>();   // tokenid -> coins
        Map<String, String> fungibleNames = new LinkedHashMap<>();
        List<JSONObject> wholes = new ArrayList<>();
        MiniNumber minimaTotal = MiniNumber.ZERO;
        Map<String, MiniNumber> tokenTotals = new LinkedHashMap<>();

        for (Object o : zCoins) {
            JSONObject c = (JSONObject) o;
            String coinid = Nft.str(c, "coinid");
            String addr   = Nft.str(c, "address");

            if (zPendingCoinIds != null && zPendingCoinIds.contains(coinid)) {
                plan.skipped.add(coinid + " — already confirming from an earlier send");
                continue;
            }
            if (zKeys.keyIndexFor(addr) == null) {
                plan.skipped.add(coinid + " — address " + addr + " is not in this wallet");
                continue;
            }

            String tokenid = Nft.str(c, "tokenid");
            if (Util.isMinima(tokenid)) {
                minima.add(c);
                minimaTotal = minimaTotal.add(new MiniNumber(Nft.str(c, "amount")));
                continue;
            }

            JSONObject tokNode = (c.get("token") instanceof JSONObject) ? (JSONObject) c.get("token") : null;
            Nft.Meta meta = Nft.meta(tokNode);
            if (Nft.isUnstampedLocked(meta, c)) {
                plan.skipped.add(meta.name + " (" + coinid + ") — UNSTAMPED locked edition; the "
                        + "creator could reclaim it. Stamp it in NFT wallet first");
                continue;
            }

            boolean hasState   = !Nft.rawStateEntries(c).isEmpty();
            boolean plainToken = normalizeScript(meta.script).equals(PLAIN_TOKEN_SCRIPT);
            if (hasState || !plainToken) {
                wholes.add(c);
            } else {
                fungible.computeIfAbsent(tokenid, k -> new ArrayList<>()).add(c);
                fungibleNames.put(tokenid, meta.name);
                MiniNumber disp = tokenTotals.getOrDefault(tokenid, MiniNumber.ZERO);
                String ta = Nft.str(c, "tokenamount");
                tokenTotals.put(tokenid, disp.add(new MiniNumber(ta.isEmpty() ? Nft.str(c, "amount") : ta)));
            }
        }

        // 1. NFTs / state / scripted coins — one transaction each, state replayed verbatim.
        int nftCount = 0;
        for (JSONObject c : wholes) {
            JSONObject tokNode = (c.get("token") instanceof JSONObject) ? (JSONObject) c.get("token") : null;
            Nft.Meta meta = Nft.meta(tokNode);
            int idx = Nft.itemIndex(c);
            plan.jobs.add(new Job(true, Nft.str(c, "tokenid"), listOf(c),
                    meta.name + (idx >= 0 ? " #" + idx : "")));
            plan.signatures++;
            nftCount++;
        }
        if (nftCount > 0) plan.totals.put("NFT / state items", nftCount + " item(s), sent whole");

        // 2. Plain fungible tokens — pooled, chunked.
        for (Map.Entry<String, List<JSONObject>> e : fungible.entrySet()) {
            String name = fungibleNames.get(e.getKey());
            plan.totals.put("Token \"" + name + "\"",
                    tokenTotals.get(e.getKey()) + " (" + e.getValue().size() + " coin(s))");
            chunkInto(plan, e.getValue(), zKeys, e.getKey(), name);
        }

        // 3. Native Minima last.
        if (!minima.isEmpty()) {
            plan.totals.put("Minima", minimaTotal + " (" + minima.size() + " coin(s))");
            chunkInto(plan, minima, zKeys, Util.MINIMA_TOKENID, "Minima");
        }

        return plan;
    }

    /** Chunk a token's coins into pooled jobs: same-address coins grouped (one signature covers
     *  them all), at most {@link #MAX_KEYS_PER_TXN} keys and {@link #MAX_INPUTS_PER_TXN} inputs. */
    private static void chunkInto(Plan zPlan, List<JSONObject> zCoins, KeyLookup zKeys,
                                  String zTokenid, String zName) {
        // Group same-address coins together so each chunk needs the fewest signatures.
        zCoins.sort((a, b) -> Nft.str(a, "address").compareTo(Nft.str(b, "address")));

        List<JSONObject> chunk = new ArrayList<>();
        List<String> chunkAddrs = new ArrayList<>();
        int part = 1;
        for (JSONObject c : zCoins) {
            String addr = Nft.str(c, "address");
            boolean newKey = !chunkAddrs.contains(addr);
            if (!chunk.isEmpty()
                    && (chunk.size() >= MAX_INPUTS_PER_TXN
                        || (newKey && chunkAddrs.size() >= MAX_KEYS_PER_TXN))) {
                zPlan.jobs.add(new Job(false, zTokenid, chunk, zName + " (part " + part++ + ")"));
                zPlan.signatures += chunkAddrs.size();
                chunk = new ArrayList<>();
                chunkAddrs = new ArrayList<>();
            }
            chunk.add(c);
            if (!chunkAddrs.contains(addr)) chunkAddrs.add(addr);
        }
        if (!chunk.isEmpty()) {
            zPlan.jobs.add(new Job(false, zTokenid, chunk, part == 1 ? zName : zName + " (part " + part + ")"));
            zPlan.signatures += chunkAddrs.size();
        }
    }

    /** Collapse whitespace + trim so formatting differences never misclassify a plain script. */
    static String normalizeScript(String zScript) {
        return zScript == null ? "" : zScript.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    private static List<JSONObject> listOf(JSONObject c) {
        List<JSONObject> l = new ArrayList<>(1);
        l.add(c);
        return l;
    }

    // =============================================================================================
    // Flow — prompt → review → execute → confirm on-chain → retry
    // =============================================================================================

    private final MainActivity act;

    public SweepFlow(MainActivity zAct) { act = zAct; }

    /** Entry point: plan from the last coin load and walk the user through it. */
    public void start() {
        if (!act.isPaired() || act.coins() == null) {
            Toast.makeText(act, "Pair with your node first — the coin list is needed to sweep",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Plan plan = plan(act.coins(), act::keyIndexForAddress, NftView.pendingCoinIds());
        if (plan.isEmpty()) {
            String why = plan.skipped.isEmpty() ? "This wallet holds no coins."
                    : "Nothing sendable:\n" + joinLines(plan.skipped);
            showReport("Nothing to sweep", why, null, null);
            return;
        }
        promptDestination(plan);
    }

    private void promptDestination(final Plan zPlan) {
        final EditText addr = new EditText(act);
        addr.setHint("Destination address (Mx… or 0x…)");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setTextColor(Design.text());
        TextView note = new TextView(act);
        note.setTextColor(Design.text());
        note.setTextSize(13f);
        note.setText("EVERYTHING in this wallet — all Minima, all tokens, every NFT with its "
                + "state — will be sent to this ONE address, in " + zPlan.jobs.size()
                + " transaction(s). Typically this is a fresh address on a NEW seed.");
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(note);
        wrap.addView(addr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Sweep everything")
                .setView(wrap)
                .setNeutralButton("Scan QR", (d, w) -> act.scanQr(s -> review(zPlan, s == null ? "" : s.trim())))
                .setPositiveButton("Review", (d, w) -> review(zPlan, addr.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void review(final Plan zPlan, final String zDest) {
        try {
            if (!Util.isValidAddress(zDest)) throw new IllegalArgumentException("Invalid destination address");

            StringBuilder sb = new StringBuilder();
            sb.append("SWEEP EVERYTHING to:\n").append(zDest).append("\n");
            try {
                String d0x = TxnFactory.parseAddress(zDest).to0xString();
                Integer ki = act.keyIndexForAddress(d0x);
                if (ki != null) {
                    sb.append("\n⚠ That is THIS wallet's own address (key ").append(ki)
                      .append(") — the sweep would send the coins back to the wallet you are emptying.\n");
                }
            } catch (Exception ignore) { }

            sb.append("\nMoving:\n");
            for (Map.Entry<String, String> e : zPlan.totals.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            if (!zPlan.skipped.isEmpty()) {
                sb.append("\nSkipping ").append(zPlan.skipped.size()).append(" coin(s):\n")
                  .append(joinLines(zPlan.skipped)).append("\n");
            }

            sb.append("\n").append(zPlan.jobs.size()).append(" transaction(s), ")
              .append(zPlan.signatures).append(" one-time signature(s):");
            List<Integer> seen = new ArrayList<>();
            for (Job j : zPlan.jobs) {
                for (JSONObject c : j.coins) {
                    Integer ki = act.keyIndexForAddress(Nft.str(c, "address"));
                    if (ki != null && !seen.contains(ki)) {
                        seen.add(ki);
                        sb.append("\n  Key ").append(ki).append(": next signature #")
                          .append(act.keyUses().currentUses(ki)).append(" of ").append(Util.WOTS_MAX_USES);
                    }
                }
            }

            sb.append("\n\nTransactions broadcast one by one; you can stop between them. Nothing is "
                    + "called swept until the chain confirms it.\n\nThis is IRREVERSIBLE. Once "
                    + "broadcast it cannot be undone.");

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
                    .setTitle("Confirm sweep — review carefully")
                    .setView(wrap)
                    .setPositiveButton("Sign & broadcast all", (d, w) -> run(zPlan, zDest))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Execute — sequential build+sign → publish, same contract as the collection send
    // ---------------------------------------------------------------------------------------------

    private void run(final Plan zPlan, final String zDest) {
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
                .setTitle("Sweeping")
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton("Stop after current", (d, w) -> cancelled[0] = true)
                .create();
        pd.show();
        //A Dozing phone drops its peers and mined transactions evaporate before block
        //inclusion. Hold the screen through the whole batch.
        if (pd.getWindow() != null) {
            pd.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        next(zPlan, zDest, 0, new ArrayList<>(), new ArrayList<>(), progress, pd, cancelled);
    }

    private void next(final Plan zPlan, final String zDest, final int i,
                      final List<String> txpowids, final List<String> sentCoinIds,
                      final TextView progress, final androidx.appcompat.app.AlertDialog pd,
                      final boolean[] cancelled) {
        if (i >= zPlan.jobs.size() || cancelled[0]) {
            finish(zPlan, zDest, i, txpowids, sentCoinIds, null, pd);
            return;
        }
        final Job job = zPlan.jobs.get(i);
        progress.setText(job.label + "  (" + (i + 1) + " of " + zPlan.jobs.size() + ")\n"
                + "Signing and broadcasting…");

        //Resolve inputs BYTE-EXACT (coinexport for every custom-token coin) before signing.
        act.resolveInputs(job.coins, new MainActivity.InputsCb() {
            @Override public void onResolved(List<TxnFactory.InputCoin> inputs) {
                final TxnFactory.BuiltTxn built;
                try {
                    try {
                        if (job.whole) {
                            built = act.factory().buildNftTransfer(inputs.get(0), zDest, Util.newTxnId());
                        } else {
                            MiniNumber sum = MiniNumber.ZERO;
                            for (TxnFactory.InputCoin in : inputs) sum = sum.add(in.getAmount());
                            //Exact-sum send: zero change, the whole chunk moves.
                            built = act.factory().buildSend(inputs, zDest, sum,
                                    new MiniData(job.tokenid), MiniNumber.ZERO, Util.newTxnId());
                        }
                    } finally {
                        //The exportable keyuses snapshot must never lag a (partial) sign.
                        try { act.vault().syncKeyUses(); }
                        catch (Exception snapEx) {
                            android.util.Log.w("wallet", "keyuses snapshot resync failed (safe to lag)", snapEx);
                        }
                    }
                } catch (Exception e) {
                    finish(zPlan, zDest, i, txpowids, sentCoinIds, job.label + ": " + e.getMessage(), pd);
                    return;
                }
                act.node().publish(built, new NodeApi.Cb() {
                    @Override public void onResult(org.json.JSONObject json) {
                        for (JSONObject c : job.coins) {
                            NftView.markPending(Nft.str(c, "coinid"));
                            sentCoinIds.add(Nft.str(c, "coinid"));
                        }
                        txpowids.add(Util.extractTxpowid(json, built.getID()));
                        next(zPlan, zDest, i + 1, txpowids, sentCoinIds, progress, pd, cancelled);
                    }
                    @Override public void onError(String message) {
                        finish(zPlan, zDest, i, txpowids, sentCoinIds, job.label + " broadcast failed: "
                                + message + " (its signatures are consumed — those leaves are safely skipped)", pd);
                    }
                });
            }
            @Override public void onError(String message) {
                finish(zPlan, zDest, i, txpowids, sentCoinIds,
                        job.label + " could not be prepared: " + message, pd);
            }
        });
    }

    /** Broadcasting is over (complete, stopped, or failed) — now confirm ON-CHAIN. */
    private void finish(Plan zPlan, String zDest, int zAttempted, List<String> txpowids,
                        List<String> sentCoinIds, String zError,
                        androidx.appcompat.app.AlertDialog pd) {
        pd.dismiss();
        act.reload();

        StringBuilder header = new StringBuilder();
        header.append("Broadcast ").append(txpowids.size()).append(" of ").append(zPlan.jobs.size())
              .append(" transaction(s) (").append(sentCoinIds.size()).append(" coin(s))\n");
        if (zError != null) header.append("Stopped: ").append(zError)
                .append("\nRemaining coins were NOT signed and stay in this wallet.\n");
        else if (zAttempted < zPlan.jobs.size())
            header.append("Stopped by you — remaining coins stay in this wallet.\n");

        if (sentCoinIds.isEmpty()) {
            showReport(zError == null ? "Sweep" : "Sweep stopped", header.toString(), null, null);
            return;
        }
        trackConfirmations(zDest, header.toString(), new ArrayList<>(sentCoinIds), txpowids);
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmation tracking — "swept" means ON-CHAIN, not "broadcast"
    // ---------------------------------------------------------------------------------------------

    private static final long CONFIRM_POLL_MS = 20_000;
    private static final int  CONFIRM_MAX_POLLS = 45;   // 15 minutes

    private void trackConfirmations(final String zDest, final String zHeader,
                                    final List<String> zRemaining, final List<String> txpowids) {
        final TextView msg = new TextView(act);
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTextSize(13f);
        msg.setPadding(dp(24), dp(16), dp(24), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        final boolean[] stopped = {false};
        final androidx.appcompat.app.AlertDialog td = new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Confirming on-chain")
                .setView(wrap)
                .setCancelable(false)
                .setNegativeButton("Hide (keeps confirming)", (d, w) -> stopped[0] = true)
                .create();
        td.setOnDismissListener(d -> stopped[0] = true);
        td.show();
        if (td.getWindow() != null) {
            td.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final int totalSent = zRemaining.size();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] polls = {0};

        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (stopped[0] || act.isDestroyed()) return;
                msg.setText(zHeader + "\nConfirmed " + (totalSent - zRemaining.size()) + " of "
                        + totalSent + " coin(s)…\n\nKeep this screen on — a sleeping phone can lose"
                        + " broadcast transactions before a block includes them.");
                act.node().coinsRelevant(new NodeApi.Cb() {
                    @Override public void onResult(org.json.JSONObject json) {
                        if (stopped[0] || act.isDestroyed()) return;
                        java.util.HashSet<String> live = new java.util.HashSet<>();
                        org.json.JSONArray arr = json.optJSONArray("response");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject c = arr.optJSONObject(i);
                                if (c != null) live.add(c.optString("coinid", ""));
                            }
                        }
                        zRemaining.retainAll(live);   // gone from the live set == spend confirmed
                        if (zRemaining.isEmpty()) {
                            stopped[0] = true;
                            td.dismiss();
                            act.reload();
                            StringBuilder done = new StringBuilder();
                            done.append(zHeader).append("\nAll ").append(totalSent)
                                .append(" coin(s) confirmed ON-CHAIN ✓\nSwept to:\n").append(zDest)
                                .append("\n\ntxpowids:\n");
                            for (String id : txpowids) done.append(id).append("\n");
                            showReport("Sweep complete", done.toString(), null, null);
                            return;
                        }
                        schedule();
                    }
                    @Override public void onError(String message) {
                        if (stopped[0] || act.isDestroyed()) return;
                        schedule();   // transient node hiccup — keep watching
                    }
                });
            }
            private void schedule() {
                if (++polls[0] >= CONFIRM_MAX_POLLS) {
                    stopped[0] = true;
                    td.dismiss();
                    offerRetry(zDest, zHeader, zRemaining, totalSent, txpowids);
                    return;
                }
                h.postDelayed(this, CONFIRM_POLL_MS);
            }
        };
        h.post(poll);
    }

    /** 15 minutes without full confirmation: free the stragglers and offer one-tap re-sweep. */
    private void offerRetry(final String zDest, final String zHeader,
                            final List<String> zRemaining, int totalSent, List<String> txpowids) {
        for (String id : zRemaining) NftView.clearPending(id);   // honest state: spendable again
        String body = zHeader + "\n" + (totalSent - zRemaining.size()) + " of " + totalSent
                + " coin(s) confirmed on-chain.\n" + zRemaining.size() + " did NOT confirm within 15"
                + " minutes — their transactions were likely lost before block inclusion. The coins"
                + " are still in this wallet and safe to resend.\n\ntxpowids so far:\n"
                + joinLines(txpowids);
        showReport("Some coins did not confirm", body,
                "Retry unconfirmed (" + zRemaining.size() + ")",
                () -> {
                    //Re-plan ONLY the unconfirmed coins from a fresh coin list, same destination.
                    JSONArray fresh = new JSONArray();
                    JSONArray all = act.coins();
                    if (all != null) {
                        for (Object o : all) {
                            JSONObject c = (JSONObject) o;
                            if (zRemaining.contains(Nft.str(c, "coinid"))) fresh.add(c);
                        }
                    }
                    if (fresh.isEmpty()) {
                        Toast.makeText(act, "Couldn't find the coins — refresh and sweep again",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Plan retry = plan(fresh, act::keyIndexForAddress, NftView.pendingCoinIds());
                    if (retry.isEmpty()) {
                        Toast.makeText(act, "Nothing sendable right now — refresh and sweep again",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    run(retry, zDest);
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    private void showReport(String zTitle, String zBody, String zAction, final Runnable zOnAction) {
        TextView msg = new TextView(act);
        msg.setText(zBody);
        msg.setTextIsSelectable(true);
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextColor(Design.text());
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(zTitle)
                .setView(wrap)
                .setNegativeButton("Close", null);
        if (zAction != null) b.setPositiveButton(zAction, (d, w) -> zOnAction.run());
        b.show();
    }

    private static String joinLines(List<String> zLines) {
        StringBuilder sb = new StringBuilder();
        for (String s : zLines) sb.append("  ").append(s).append("\n");
        return sb.toString();
    }

    private int dp(int zDp) {
        return Math.round(zDp * act.getResources().getDisplayMetrics().density);
    }
}
