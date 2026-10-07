package com.eurobuddha.wallet;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One on-chain transaction that touched OUR addresses, parsed from a {@code history relevant:true}
 * txpow. The node's own {@code details[].difference} is computed from the NODE wallet's keys, so we
 * ignore it and recompute the per-token net effect over THIS wallet's 64 base addresses:
 * {@code sum(outputs at ours) - sum(inputs at ours)}, in display units ({@code tokenamount} when a
 * token descriptor carries a scale, raw {@code amount} otherwise).
 */
class HistoryTx {

    String txpowid = "";
    String block = "";
    long timemilli = 0;

    /** tokenid → net effect on our addresses (display units). Insertion-ordered. */
    final LinkedHashMap<String, BigDecimal> diff = new LinkedHashMap<>();

    /** tokenid → token name (parsed from any coin's descriptor in this txn). */
    final Map<String, String> tokenNames = new LinkedHashMap<>();

    /** The other side's FULL address (first non-ours output when we sent, first non-ours input
     *  when we received), or "" for a pure self transaction. */
    String counterparty = "";

    boolean touchesOurs = false;

    /** Our biggest-magnitude token in this txn (what the row displays). */
    String primaryTokenId() {
        String best = null;
        BigDecimal bestAbs = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : diff.entrySet()) {
            BigDecimal abs = e.getValue().abs();
            if (best == null || abs.compareTo(bestAbs) > 0) { best = e.getKey(); bestAbs = abs; }
        }
        return best;
    }

    String direction() {
        String tid = primaryTokenId();
        if (tid == null) return "SELF";
        int cmp = diff.get(tid).compareTo(BigDecimal.ZERO);
        if (cmp > 0) return "RECEIVED";
        if (cmp < 0) return "SENT";
        return "SELF";
    }

    String tokenName(String zTokenId) {
        if (Util.isMinima(zTokenId)) return "Minima";
        String name = tokenNames.get(zTokenId);
        return (name == null || name.isEmpty()) ? zTokenId : name;
    }

    /** Parse one history txpow; returns a row with {@link #touchesOurs} false if none of our
     *  addresses appear (caller drops it — e.g. the paired node's own transactions). */
    static HistoryTx from(JSONObject zTxPoW, AddressBook zBook) {
        HistoryTx tx = new HistoryTx();
        tx.txpowid = zTxPoW.optString("txpowid", "");

        JSONObject header = zTxPoW.optJSONObject("header");
        if (header != null) {
            tx.block = header.optString("block", "");
            try { tx.timemilli = Long.parseLong(header.optString("timemilli", "0")); }
            catch (NumberFormatException ignore) { }
        }

        JSONObject body = zTxPoW.optJSONObject("body");
        JSONObject txn  = body == null ? null : body.optJSONObject("txn");
        if (txn == null) return tx;

        String firstForeignInput = "", firstForeignOutput = "";

        JSONArray ins  = txn.optJSONArray("inputs");
        JSONArray outs = txn.optJSONArray("outputs");
        for (int pass = 0; pass < 2; pass++) {
            JSONArray coins = pass == 0 ? ins : outs;
            if (coins == null) continue;
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin == null) continue;
                String addr = coin.optString("address", "");
                boolean ours = zBook.keyIndexFor(addr) != null;

                String tokenid = coin.optString("tokenid", "0x00");
                JSONObject token = coin.optJSONObject("token");
                if (token != null && !tx.tokenNames.containsKey(tokenid)) {
                    // name may be a plain string or an object with a "name" field
                    JSONObject nameObj = token.optJSONObject("name");
                    String name = nameObj != null ? nameObj.optString("name", "") : token.optString("name", "");
                    tx.tokenNames.put(tokenid, name);
                }

                if (!ours) {
                    if (pass == 0 && firstForeignInput.isEmpty())  firstForeignInput = addr;
                    if (pass == 1 && firstForeignOutput.isEmpty()) firstForeignOutput = addr;
                    continue;
                }
                tx.touchesOurs = true;

                // Display units: tokenamount when the descriptor scales it, else the raw amount.
                String amtStr = coin.has("tokenamount")
                        ? coin.optString("tokenamount", "0") : coin.optString("amount", "0");
                BigDecimal amt;
                try { amt = new BigDecimal(amtStr); } catch (NumberFormatException e) { continue; }
                if (pass == 0) amt = amt.negate();

                BigDecimal cur = tx.diff.get(tokenid);
                tx.diff.put(tokenid, cur == null ? amt : cur.add(amt));
            }
        }

        tx.counterparty = "RECEIVED".equals(tx.direction()) ? firstForeignInput : firstForeignOutput;
        return tx;
    }
}
