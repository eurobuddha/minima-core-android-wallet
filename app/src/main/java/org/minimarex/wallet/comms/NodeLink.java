package org.minimarex.wallet.comms;

import android.content.Context;

import org.json.JSONObject;
import org.minimarex.wallet.TxnFactory;

/**
 * NodeLink — the wallet's node relay, exposing ONLY the non-admin commands a self-custodial,
 * locally-signing wallet needs. It never calls an admin-gated WRITE command ({@code send},
 * {@code consolidate}, {@code sign}, {@code txnsign}, {@code cointrack}, {@code megammrsync}, ...):
 * the wallet signs locally and uses the node purely to read chain state and to broadcast the finished,
 * app-signed transaction. Works against a REGULAR (non-MegaMMR) node — no node seed, no MegaMMR.
 *
 * <p>Commands used (all in minima-core's non-admin allow set):
 * <ul>
 *   <li>{@code newscript trackall:true script:"<ours>"} — TRACK our full {@code RETURN SIGNEDBY(...)}
 *       script so a regular node indexes coins arriving at our address as "relevant" (forward-only;
 *       idempotent — safe to call on every startup/pair). This is what makes our coins queryable.</li>
 *   <li>{@code coins relevant:true address:<ours>} — our unspent coins from the node's relevant set
 *       (aggregated for balances and fed to {@link TxnFactory#fromCoinJson}). NO {@code megammr:true},
 *       NO fallback, NEVER {@code sendable:true} (which filters to {@code RETURN TRUE} addresses and
 *       returns EMPTY for our custom script — we hold the key, so confirmed == spendable for us).</li>
 *   <li>{@code block} / {@code status} — chain height / sync state.</li>
 *   <li>{@code tokens} — token metadata (names / icons / decimals).</li>
 *   <li>{@code txnimport} &rarr; {@code txnbasics} &rarr; {@code txnpost mine:true} (NO {@code auto:true})
 *       — publish a signed transaction in three explicit steps. The imported witness carries SIGNATURES
 *       ONLY; {@code txnbasics} adds the MMR proofs + scripts from the node's own tip MMR exactly once,
 *       then {@code txnpost} broadcasts it. Using {@code auto:true} (or importing pre-existing proofs)
 *       would duplicate MMR proofs and yield an invalid tx.</li>
 *   <li>{@code coincheck} / {@code coinimport track:true} — restore historic coins (funded before we
 *       began tracking) from an exported coin proof.</li>
 * </ul>
 *
 * <p>Thin, deliberately: it builds the exact command strings and delegates timing/threading/pairing to
 * {@link NodeApi}. Publishing is implemented but MUST be exercised on-device against a real node — no
 * unit test posts anything.
 */
public class NodeLink {

    private final NodeApi mApi;

    public NodeLink(Context zContext, NodeApi.PairingListener zPairing) {
        mApi = new NodeApi(zContext, zPairing);
    }

    /** Direct access to the underlying transport (for the pairing/register lifecycle & ad-hoc reads). */
    public NodeApi api() {
        return mApi;
    }

    // ---- reads -------------------------------------------------------------------------------

    /**
     * Run an arbitrary node command verbatim and deliver its raw response. Intended for read-only
     * diagnostics and ad-hoc reads — the caller is responsible for passing ONLY non-admin commands
     * (this wallet never issues an admin-gated write here). Threading/timeout/pairing are handled by
     * {@link NodeApi#cmd}.
     */
    public void raw(String zCommand, NodeApi.Cb zCb) {
        mApi.cmd(zCommand, zCb);
    }

    /**
     * TRACK our full script so a REGULAR node indexes coins arriving at our address as "relevant"
     * (exactly like the node's own coins). Runs {@code newscript trackall:true script:"<script>"},
     * where {@code zScript} is our complete {@code RETURN SIGNEDBY(0x<pubkey>)} script (pass
     * {@code WalletCore.getScript(0)}). Forward-only (indexes coins in FUTURE blocks; does NOT back-fill
     * coins already on-chain) and idempotent — safe to call on every startup/pair. Non-admin.
     */
    public void trackScript(String zScript, NodeApi.Cb zCb) {
        mApi.cmd("newscript trackall:true script:\"" + zScript + "\"", zCb);
    }

    /**
     * Our unspent coins at {@code zAddress} from the node's RELEVANT set — a single, non-fallback call:
     * {@code coins relevant:true address:<ours>}. The address must have been tracked (see
     * {@link #trackScript}) for a regular node to index it.
     *
     * <p>Deliberately NO {@code megammr:true} (a regular node has no MegaMMR), NO fallback, and NEVER
     * {@code sendable:true} — {@code sendable} filters to {@code RETURN TRUE} scripts and returns EMPTY
     * for our {@code RETURN SIGNEDBY(...)} script. For us every confirmed coin here is spendable (we
     * hold the key); we aggregate ourselves and ignore the node's {@code sendable}.
     */
    public void coins(final String zAddress, final NodeApi.Cb zCb) {
        coins(zAddress, null, zCb);
    }

    /**
     * As {@link #coins(String, NodeApi.Cb)} but optionally scoped to a single {@code zTokenId}
     * (appends {@code tokenid:...} when non-null/non-empty).
     */
    public void coins(final String zAddress, final String zTokenId, final NodeApi.Cb zCb) {
        String cmd = "coins relevant:true address:" + zAddress;
        if (zTokenId != null && !zTokenId.isEmpty()) {
            cmd += " tokenid:" + zTokenId;
        }
        mApi.cmd(cmd, zCb);
    }

    /** Current chain tip block. */
    public void block(NodeApi.Cb zCb) {
        mApi.cmd("block", zCb);
    }

    /** Node status (sync / chain length / etc). */
    public void status(NodeApi.Cb zCb) {
        mApi.cmd("status", zCb);
    }

    /** All token descriptors the node knows (metadata for icons / names / decimals). */
    public void tokens(NodeApi.Cb zCb) {
        mApi.cmd("tokens", zCb);
    }

    /** One token's descriptor. */
    public void token(String zTokenId, NodeApi.Cb zCb) {
        mApi.cmd("tokens tokenid:" + zTokenId, zCb);
    }

    // ---- restore historic coins (non-admin) --------------------------------------------------

    /** Validate an exported coin proof against the local MMR (read-only): {@code coincheck data:<hex>}. */
    public void coinCheck(String zDataHex, NodeApi.Cb zCb) {
        mApi.cmd("coincheck data:" + zDataHex, zCb);
    }

    /**
     * Import an exported coin proof and mark it tracked/relevant: {@code coinimport data:<hex> track:true}.
     * Used to restore a coin funded BEFORE we began tracking (from a MegaMMR host's {@code coinexport}).
     */
    public void coinImport(String zDataHex, NodeApi.Cb zCb) {
        mApi.cmd("coinimport data:" + zDataHex + " track:true", zCb);
    }

    // ---- publish (on-device only; never invoked from a unit test) ----------------------------

    /**
     * Publish a locally-built, locally-signed transaction in THREE explicit, non-{@code auto} steps:
     *
     * <ol>
     *   <li>{@code txnimport id:<id> data:0x<hex>} — import the signed Transaction+Witness (SIGNATURES
     *       ONLY — no proofs, no scripts) under its chosen id.</li>
     *   <li>{@code txnbasics id:<id>} — the node adds the MMR proofs (from its own tip MMR; the coin is
     *       there because we tracked our script — no MegaMMR) AND the scripts, exactly once.</li>
     *   <li>{@code txnpost id:<id> mine:true} — broadcast it (NO {@code auto:true}: proofs+scripts are
     *       already attached by {@code txnbasics}; {@code auto} would duplicate them).</li>
     * </ol>
     *
     * <p>Every step is non-admin. The {@code status} field is checked after each: on any non-true status
     * or transport error the verbatim error is surfaced and the sequence STOPS. Exercised on-device
     * against a real node — do NOT call from a unit test.
     *
     * @param zBuilt the finished transaction from {@link TxnFactory}.
     * @param zCb    receives the {@code txnpost} response (which carries the txpowid), or the first error.
     */
    public void publish(final TxnFactory.BuiltTxn zBuilt, final NodeApi.Cb zCb) {
        final String importCmd = zBuilt.getTxnImportCommand();
        final String basicsCmd = "txnbasics id:" + zBuilt.getID();
        final String postCmd   = zBuilt.getTxnPostCommand();

        // Step 1 — txnimport (signatures-only witness).
        mApi.cmd(importCmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject importResp) {
                if (!importResp.optBoolean("status", false)) {
                    if (zCb != null) zCb.onError("txnimport failed: " + importResp.optString("error", importResp.toString()));
                    return;
                }
                // Step 2 — txnbasics: node adds MMR proofs + scripts once.
                mApi.cmd(basicsCmd, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject basicsResp) {
                        if (!basicsResp.optBoolean("status", false)) {
                            if (zCb != null) zCb.onError("txnbasics failed: " + basicsResp.optString("error", basicsResp.toString()));
                            return;
                        }
                        // Step 3 — txnpost (mine:true, NO auto).
                        mApi.cmd(postCmd, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject postResp) {
                                if (!postResp.optBoolean("status", false)) {
                                    if (zCb != null) zCb.onError("txnpost failed: " + postResp.optString("error", postResp.toString()));
                                    return;
                                }
                                if (zCb != null) zCb.onResult(postResp);
                            }
                            @Override public void onError(String message) { if (zCb != null) zCb.onError(message); }
                        });
                    }
                    @Override public void onError(String message) { if (zCb != null) zCb.onError(message); }
                });
            }
            @Override public void onError(String message) { if (zCb != null) zCb.onError(message); }
        });
    }

    public void onDestroy() {
        mApi.onDestroy();
    }
}
