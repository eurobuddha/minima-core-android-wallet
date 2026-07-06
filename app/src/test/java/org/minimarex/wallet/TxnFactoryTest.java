package org.minimarex.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.Coin;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.objects.mmr.MMREntryNumber;

/**
 * Pure-JVM (no Android, no node) verification for M1 — the transaction factory.
 *
 * <p>Each operation (Send, Split N=4, Consolidate 3-in) is built from a fixed test seed + hand-made
 * input coins at our derived address, and checked for: correct outputs/amounts/change; a stable
 * transaction id; a SIGNATURES-ONLY Witness (the expected Signature(s), and NO ScriptProofs, NO
 * CoinProofs — the node adds scripts+proofs at {@code txnbasics}); a {@code txnimport} hex that
 * round-trips byte-identically through {@link TxnRow#convertMiniDataVersion} with the signature still
 * verifying; and a KeyUses counter that advances by exactly one signature per distinct input address
 * (never reusing a leaf).
 */
public class TxnFactoryTest {

    private static final String PHRASE = "minima wallet test seed phrase one";
    private static final MiniData MINIMA = new MiniData("0x00");

    /** In-memory {@link KeyUses}: same reserve-before-sign / MAX semantics as PrefsKeyUses. */
    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> mUses = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = mUses.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); mUses.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { mUses.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new HashMap<>(mUses); }
    }

    private static MiniData cid(String zHex) {
        return new MiniData(zHex);
    }

    /** MiniNumber has no equals() override, so compare by value. */
    private static void assertAmt(String msg, String expected, MiniNumber actual) {
        assertTrue(msg + " (expected " + expected + " but was " + actual + ")",
                new MiniNumber(expected).isEqual(actual));
    }

    /** A native-Minima input coin at OUR primary address (key index 0). */
    private static TxnFactory.InputCoin ourCoin(WalletCore w, String coinid, String amount, int mmrEntry) {
        MiniData ourAddr = w.getReceiveAddress().getAddressData();
        return new TxnFactory.InputCoin(cid(coinid), ourAddr, new MiniNumber(amount),
                new MMREntryNumber(mmrEntry));
    }

    // =============================================================================================
    // SEND
    // =============================================================================================
    @Test
    public void send_buildsSignsAndRoundTrips() throws Exception {

        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore wallet = new WalletCore(PHRASE, uses);
        TxnFactory factory = new TxnFactory(wallet);

        MiniData pubkey = wallet.getPublicKey(0);

        // Inputs: 50 + 30 = 80 (raw Minima) at our address.
        List<TxnFactory.InputCoin> inputs = new ArrayList<>();
        inputs.add(ourCoin(wallet,
                "0xAA11111111111111111111111111111111111111111111111111111111111111", "50", 10));
        inputs.add(ourCoin(wallet,
                "0xBB22222222222222222222222222222222222222222222222222222222222222", "30", 11));

        // Recipient = an independent address (key index 5 of a second wallet), 0x form.
        WalletCore other = new WalletCore("some other unrelated wallet seed phrase", new InMemoryKeyUses());
        String recipient = other.getAddress(5).getAddressData().to0xString();

        // amount 60, burn 0.1 (native-Minima, built into the txn as inputs-outputs).
        MiniNumber amount = new MiniNumber("60");
        MiniNumber burn   = new MiniNumber("0.1");

        assertEquals("uses starts at 0", 0, uses.currentUses(0));

        TxnFactory.BuiltTxn built = factory.buildSend(inputs, recipient, amount, MINIMA, burn, "send1");

        Transaction txn = built.getTransaction();

        System.out.println("=== M1 VERIFY: SEND ===");
        System.out.println("txid          : " + built.getTransactionID().to0xString());
        System.out.println("import hex len : " + built.getImportHex().length());
        System.out.println("num signatures : " + built.getNumSignatures());
        System.out.println("txnimport      : " + built.getTxnImportCommand().substring(0, 40) + "...");
        System.out.println("txnpost        : " + built.getTxnPostCommand());

        // --- outputs / amounts / change ---------------------------------------------------------
        ArrayList<Coin> outs = txn.getAllOutputs();
        assertEquals("send: recipient + change = 2 outputs", 2, outs.size());
        // output 0 = recipient 60 to recipient addr
        assertAmt("recipient amount", "60", outs.get(0).getAmount());
        assertTrue("recipient address", outs.get(0).getAddress().isEqual(new MiniData(recipient)));
        // output 1 = change 80 - 60 - 0.1(burn) = 19.9 back to us
        assertAmt("change amount", "19.9", outs.get(1).getAmount());
        assertTrue("change to our address",
                outs.get(1).getAddress().isEqual(wallet.getReceiveAddress().getAddressData()));
        // burn is the input-output difference
        assertAmt("built-in burn = 0.1", "0.1", txn.getBurn());

        // --- witness shape: SIGNATURES ONLY (1 sig, 0 scripts, 0 coinproofs) --------------------
        assertEquals("one signature", 1, built.getNumSignatures());
        assertEquals("witness has 1 signature", 1, built.getWitness().getAllSignatures().size());
        assertTrue("witness has NO script proofs (node adds them at txnbasics)",
                built.getWitness().getAllScripts().isEmpty());
        assertTrue("witness has NO coin proofs", built.getWitness().getAllCoinProofs().isEmpty());

        // --- KeyUses advanced by exactly the number of signatures -------------------------------
        assertEquals("uses advanced by 1", 1, uses.currentUses(0));

        // --- stable transaction id --------------------------------------------------------------
        MiniData txid = built.getTransactionID();
        txn.calculateTransactionID();
        assertTrue("txid stable on recompute", txn.getTransactionID().isEqual(txid));

        // --- round-trip byte-identical + signature still verifies -------------------------------
        assertRoundTripVerifies(built, wallet, pubkey);

        // --- a SECOND identical build uses the NEXT leaf (no reuse) -----------------------------
        Signature firstSig = built.getWitness().getAllSignatures().get(0);
        TxnFactory.BuiltTxn built2 = factory.buildSend(inputs, recipient, amount, MINIMA, burn, "send1b");
        assertEquals("uses advanced to 2", 2, uses.currentUses(0));
        Signature secondSig = built2.getWitness().getAllSignatures().get(0);
        assertNotEquals("distinct leaves -> distinct WOTS signatures",
                lastProof(firstSig), lastProof(secondSig));
        System.out.println("no-reuse       : OK (leaf 0 then leaf 1; counter now " + uses.currentUses(0) + ")");
    }

    // =============================================================================================
    // SPLIT (N = 4)
    // =============================================================================================
    @Test
    public void split_buildsFourEqualOutputsPlusChange() throws Exception {

        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore wallet = new WalletCore(PHRASE, uses);
        TxnFactory factory = new TxnFactory(wallet);
        MiniData pubkey = wallet.getPublicKey(0);

        // Inputs 60 + 40 = 100.
        List<TxnFactory.InputCoin> inputs = new ArrayList<>();
        inputs.add(ourCoin(wallet,
                "0xCC33333333333333333333333333333333333333333333333333333333333333", "60", 20));
        inputs.add(ourCoin(wallet,
                "0xDD44444444444444444444444444444444444444444444444444444444444444", "40", 21));

        // Split 40 into N=4 self-outputs; change = 100 - 40 = 60.
        TxnFactory.BuiltTxn built = factory.buildSplit(inputs, new MiniNumber("40"), 4, MINIMA,
                MiniNumber.ZERO, "split1");
        Transaction txn = built.getTransaction();

        System.out.println("=== M1 VERIFY: SPLIT (N=4) ===");
        System.out.println("txid          : " + built.getTransactionID().to0xString());
        System.out.println("outputs        : " + txn.getAllOutputs().size());
        System.out.println("import hex len : " + built.getImportHex().length());

        ArrayList<Coin> outs = txn.getAllOutputs();
        // 4 equal split coins of 10, remainder 0, + 1 change of 60 = 5 outputs.
        assertEquals("4 split + 1 change = 5 outputs", 5, outs.size());
        MiniData self = wallet.getReceiveAddress().getAddressData();
        MiniNumber splitSum = MiniNumber.ZERO;
        for (int i = 0; i < 4; i++) {
            assertAmt("split coin " + i + " = 10", "10", outs.get(i).getAmount());
            assertTrue("split coin to self", outs.get(i).getAddress().isEqual(self));
            splitSum = splitSum.add(outs.get(i).getAmount());
        }
        assertAmt("split coins sum to 40", "40", splitSum);
        assertAmt("change = 60", "60", outs.get(4).getAmount());
        // No burn: inputs == outputs
        assertAmt("no burn", "0", txn.getBurn());
        assertAmt("outputs sum to inputs", "100", txn.sumOutputs());

        assertEquals("one signature", 1, built.getNumSignatures());
        assertTrue("no script proofs (node adds them at txnbasics)",
                built.getWitness().getAllScripts().isEmpty());
        assertTrue("no coin proofs", built.getWitness().getAllCoinProofs().isEmpty());
        assertEquals("uses advanced by 1", 1, uses.currentUses(0));

        assertRoundTripVerifies(built, wallet, pubkey);

        // --- also exercise the remainder path: 10 / 3 is not exact ------------------------------
        WalletCore w2 = new WalletCore(PHRASE, new InMemoryKeyUses());
        TxnFactory f2 = new TxnFactory(w2);
        List<TxnFactory.InputCoin> in2 = new ArrayList<>();
        in2.add(ourCoin(w2,
                "0xEE55555555555555555555555555555555555555555555555555555555555555", "20", 30));
        // Split 10 into 3 -> 3 coins of (10/3 truncated) + a remainder coin; change = 20 - 10 = 10.
        TxnFactory.BuiltTxn r = f2.buildSplit(in2, new MiniNumber("10"), 3, MINIMA, MiniNumber.ZERO, "split3");
        ArrayList<Coin> routs = r.getTransaction().getAllOutputs();
        // 3 split + 1 remainder + 1 change = 5
        assertEquals("uneven split: 3 + remainder + change = 5 outputs", 5, routs.size());
        MiniNumber first3 = routs.get(0).getAmount().add(routs.get(1).getAmount()).add(routs.get(2).getAmount());
        MiniNumber remainder = routs.get(3).getAmount();
        assertAmt("3 splits + remainder = 10", "10", first3.add(remainder));
        assertTrue("remainder > 0 (10/3 not exact)", remainder.isMore(MiniNumber.ZERO));
        assertAmt("uneven-split change = 10", "10", routs.get(4).getAmount());
        System.out.println("remainder path : OK (split 10/3 -> " + routs.get(0).getAmount()
                + " x3 + remainder " + remainder + ")");
    }

    // =============================================================================================
    // CONSOLIDATE (3 inputs -> 1 output)
    // =============================================================================================
    @Test
    public void consolidate_mergesThreeInputsIntoOne() throws Exception {

        InMemoryKeyUses uses = new InMemoryKeyUses();
        WalletCore wallet = new WalletCore(PHRASE, uses);
        TxnFactory factory = new TxnFactory(wallet);
        MiniData pubkey = wallet.getPublicKey(0);

        // Three small coins 1 + 2 + 3 = 6.
        List<TxnFactory.InputCoin> inputs = new ArrayList<>();
        inputs.add(ourCoin(wallet,
                "0x1111111111111111111111111111111111111111111111111111111111111111", "1", 40));
        inputs.add(ourCoin(wallet,
                "0x2222222222222222222222222222222222222222222222222222222222222222", "2", 41));
        inputs.add(ourCoin(wallet,
                "0x3333333333333333333333333333333333333333333333333333333333333333", "3", 42));

        TxnFactory.BuiltTxn built = factory.buildConsolidate(inputs, MINIMA, MiniNumber.ZERO, "consol1");
        Transaction txn = built.getTransaction();

        System.out.println("=== M1 VERIFY: CONSOLIDATE (3 -> 1) ===");
        System.out.println("txid          : " + built.getTransactionID().to0xString());
        System.out.println("inputs         : " + txn.getAllInputs().size());
        System.out.println("outputs        : " + txn.getAllOutputs().size());
        System.out.println("import hex len : " + built.getImportHex().length());

        assertEquals("3 inputs", 3, txn.getAllInputs().size());
        ArrayList<Coin> outs = txn.getAllOutputs();
        assertEquals("consolidate -> 1 output", 1, outs.size());
        assertAmt("output = sum 6", "6", outs.get(0).getAmount());
        assertTrue("output to self", outs.get(0).getAddress().isEqual(wallet.getReceiveAddress().getAddressData()));
        assertAmt("no change, no burn", "0", txn.getBurn());

        // All 3 inputs share ONE address -> exactly ONE signature.
        assertEquals("one signature (single distinct input address)", 1, built.getNumSignatures());
        assertTrue("no script proofs (node adds them at txnbasics)",
                built.getWitness().getAllScripts().isEmpty());
        assertTrue("no coin proofs", built.getWitness().getAllCoinProofs().isEmpty());
        assertEquals("uses advanced by exactly 1", 1, uses.currentUses(0));

        assertRoundTripVerifies(built, wallet, pubkey);
    }

    // =============================================================================================
    // Shared assertions
    // =============================================================================================

    /**
     * The {@code txnimport} MiniData must round-trip byte-identically, preserve the id, keep NO coin
     * proofs, recompute the same transaction id, and the recovered signature(s) must still verify
     * against our derived key (root public key == our pubkey; full Winternitz verify true).
     */
    private void assertRoundTripVerifies(TxnFactory.BuiltTxn built, WalletCore wallet, MiniData pubkey) {
        MiniData serialized = built.getImportData();
        MiniData txid = built.getTransactionID();

        TxnRow back = TxnRow.convertMiniDataVersion(serialized);
        MiniData reserialized = MiniData.getMiniDataVersion(back);

        assertTrue("round-trip byte-identical", serialized.isEqual(reserialized));
        assertEquals("round-trip preserves id", built.getID(), back.getID());

        Transaction backTxn = back.getTransaction();
        assertTrue("round-trip preserves txid", backTxn.getTransactionID().isEqual(txid));

        Witness backWit = back.getWitness();
        assertTrue("round-trip: still NO coin proofs", backWit.getAllCoinProofs().isEmpty());
        assertTrue("round-trip: still NO script proofs", backWit.getAllScripts().isEmpty());
        assertEquals("round-trip: same signature count",
                built.getNumSignatures(), backWit.getAllSignatures().size());

        for (Signature sig : backWit.getAllSignatures()) {
            assertTrue("recovered sig root pubkey == our pubkey", sig.getRootPublicKey().isEqual(pubkey));
            assertTrue("recovered sig verifies (Winternitz) against txid",
                    wallet.deriveTreeKey(0).verify(txid, sig));
        }
        System.out.println("round-trip     : OK (byte-identical, txid stable, sig verifies, no coinproofs)");
    }

    /** The last (root-level) Winternitz signature bytes of a WOTS Signature — differs per leaf. */
    private static String lastProof(Signature sig) {
        return sig.getAllSignatureProofs().get(sig.getAllSignatureProofs().size() - 1)
                .getSignature().to0xString();
    }
}
