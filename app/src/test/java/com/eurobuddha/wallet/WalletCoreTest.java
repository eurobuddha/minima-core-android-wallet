package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.Coin;
import org.minima.objects.ScriptProof;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;

/**
 * Pure-JVM (no Android) verification for M0. Exercises:
 *   (b) deterministic derivation + address + sign/verify
 *   (c) Transaction+Witness serialization round-trip (the txnimport hex path)
 * plus determinism (derive twice → identical) and the KeyUses reserve-before-sign contract.
 */
public class WalletCoreTest {

    private static final String PHRASE = "minima wallet test seed phrase one";

    /** In-memory {@link KeyUses} for tests — same MAX / reserve-before-sign semantics as PrefsKeyUses. */
    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> mUses = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = mUses.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); mUses.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { mUses.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new HashMap<>(mUses); }
    }

    @Test
    public void deriveSignVerifyAndRoundTrip() throws Exception {

        // ---- (b) derivation + address ------------------------------------------------------
        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore wallet = new WalletCore(PHRASE, uses);

        MiniData baseSeed = wallet.getBaseSeed();
        MiniData pubkey   = wallet.getPublicKey(0);
        String script     = wallet.getScript(0);
        String mxAddr     = wallet.getAddress(0).getMinimaAddress();
        String hexAddr    = wallet.getAddress(0).getAddressData().to0xString();

        System.out.println("=== M0 VERIFY (b): derivation ===");
        System.out.println("phrase      : " + PHRASE);
        System.out.println("baseSeed    : " + baseSeed.to0xString());
        System.out.println("pubkey[0]   : " + pubkey.to0xString());
        System.out.println("script[0]   : " + script);
        System.out.println("Mx address  : " + mxAddr);
        System.out.println("0x address  : " + hexAddr);

        assertTrue("Mx address form", mxAddr.startsWith("Mx"));
        assertTrue("0x address form", hexAddr.startsWith("0x"));

        // ---- determinism: derive again, must be identical ---------------------------------
        WalletCore wallet2 = new WalletCore(PHRASE, new InMemoryKeyUses());
        assertEquals("pubkey deterministic",  pubkey.to0xString(),  wallet2.getPublicKey(0).to0xString());
        assertEquals("Mx addr deterministic", mxAddr,               wallet2.getAddress(0).getMinimaAddress());
        assertEquals("0x addr deterministic", hexAddr,              wallet2.getAddress(0).getAddressData().to0xString());
        System.out.println("determinism : OK (pubkey + Mx + 0x identical on a second derivation)");

        // ---- (b) sign a fixed 32-byte txid + verify ---------------------------------------
        MiniData txid = new MiniData("0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20");
        assertEquals("txid is 32 bytes", 32, txid.getLength());

        assertEquals("uses starts at 0", 0, uses.currentUses(0));
        Signature sig = wallet.signTransactionID(txid, 0);
        assertEquals("uses advanced to 1 (reserve-before-sign)", 1, uses.currentUses(0));

        // The signature's root public key must equal our derived key's root public key.
        assertTrue("sig root pubkey == derived pubkey", sig.getRootPublicKey().isEqual(pubkey));

        // Full WOTS verify against a fresh TreeKey for the same index.
        TreeKey verifyKey = wallet.deriveTreeKey(0);
        assertTrue("WOTS verify", verifyKey.verify(txid, sig));

        System.out.println("=== M0 VERIFY (b): sign + verify ===");
        System.out.println("txid        : " + txid.to0xString());
        System.out.println("sig rootkey : " + sig.getRootPublicKey().to0xString());
        System.out.println("verify      : OK (Winternitz verify true; root pubkey matches)");

        // ---- (c) Transaction + Witness serialization round-trip ----------------------------
        Transaction txn = new Transaction();
        // 1 dummy input coin AT OUR ADDRESS (so the ScriptProof matches), 1 output coin.
        MiniData ourAddr  = wallet.getAddress(0).getAddressData();
        MiniData tokenid  = new MiniData("0x00");
        MiniData dummyCid = new MiniData("0xAABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899");
        txn.addInput(new Coin(dummyCid, ourAddr, new MiniNumber("10"), tokenid));
        MiniData destAddr = wallet2.getAddress(1).getAddressData();
        txn.addOutput(new Coin(destAddr, new MiniNumber("9.9"), tokenid));

        Witness wit = new Witness();
        wit.addSignature(sig);
        wit.addScript(new ScriptProof(script));   // our RETURN SIGNEDBY(...) script proof

        TxnRow row = new TxnRow("m0-test", txn, wit);

        // Serialize to the txnimport hex (MiniData version), then read it back.
        MiniData serialized = MiniData.getMiniDataVersion(row);
        TxnRow back = TxnRow.convertMiniDataVersion(serialized);
        MiniData reserialized = MiniData.getMiniDataVersion(back);

        System.out.println("=== M0 VERIFY (c): serialization round-trip ===");
        System.out.println("serialized bytes : " + serialized.getLength());
        System.out.println("hex (first 96)   : " + serialized.to0xString().substring(0, Math.min(96, serialized.to0xString().length())) + "...");
        System.out.println("round-trip equal : " + serialized.isEqual(reserialized));

        assertEquals("round-trip id",     "m0-test", back.getID());
        assertTrue("round-trip byte-equal", serialized.isEqual(reserialized));
        // And the recovered signature still verifies against our key.
        assertTrue("recovered sig verifies",
            wallet.deriveTreeKey(0).verify(txid, back.getWitness().getAllSignatures().get(0)));
        System.out.println("recovered sig verifies : OK");

        // ---- KeyUses contract: two signs never reuse a leaf --------------------------------
        Signature sigB = wallet.signTransactionID(txid, 0);
        assertEquals("uses advanced to 2", 2, uses.currentUses(0));
        assertNotEquals("distinct leaves → distinct signatures",
            sig.getAllSignatureProofs().get(sig.getAllSignatureProofs().size() - 1).getSignature().to0xString(),
            sigB.getAllSignatureProofs().get(sigB.getAllSignatureProofs().size() - 1).getSignature().to0xString());
        System.out.println("keyuses     : OK (leaf 0 then leaf 1; counter now " + uses.currentUses(0) + ")");
    }
}
