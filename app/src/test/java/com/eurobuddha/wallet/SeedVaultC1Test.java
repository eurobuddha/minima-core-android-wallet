package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Pure-JVM verification of the C1 fix and the related MINORs, exercising the real {@link SeedVault}
 * state machine via its package-private injectable {@link SeedVault.BlobStore} (no Android needed).
 *
 * <p>Proves:
 * <ul>
 *   <li><b>C1</b> — after {@link SeedVault#importVault}, the vault is UNTRUSTED and
 *       {@link SeedVault#assertSigningAllowed()} throws until the explicit
 *       {@link SeedVault#attestKeyUsesUpToDate()} attestation; and the restore still MAX-reconciles the
 *       counter (raises, never lowers).</li>
 *   <li><b>createNew stays trusted</b> — a freshly generated seed signs without attestation.</li>
 *   <li><b>open() distinguishes</b> a wrong passphrase (returns false) from an unreadable/corrupt vault
 *       ({@link SeedVault.VaultCorruptException}).</li>
 *   <li><b>Multi-index snapshot</b> — the exported snapshot enumerates every known key index, not just 0.</li>
 * </ul>
 */
public class SeedVaultC1Test {

    private static final String SEED = "minima wallet portable seed phrase for the c1 test";
    private static final String PASS = "correct horse battery staple";

    /** In-memory {@link KeyUses} — same MAX / reserve-before-sign semantics as PrefsKeyUses. */
    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> mUses = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = mUses.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); mUses.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { mUses.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new LinkedHashMap<>(mUses); }
    }

    /** In-memory {@link SeedVault.BlobStore} — durable enough for tests. */
    static final class MemBlobStore implements SeedVault.BlobStore {
        private String mHex;
        public boolean contains() { return mHex != null; }
        public String read() { return mHex; }
        public void write(String hex) { mHex = hex; }
    }

    private static byte[] backupOf(String seed, boolean trusted, Map<Integer, Integer> uses) throws Exception {
        return VaultCrypto.encrypt(PASS, new VaultBlob(seed, trusted, uses).toBytes());
    }

    // =============================================================================================
    // C1: importVault is untrusted, blocks signing until attested, and MAX-reconciles the counter
    // =============================================================================================
    @Test
    public void importVault_untrustedAndBlocked_untilAttested() throws Exception {
        InMemoryKeyUses live = new InMemoryKeyUses();
        SeedVault vault = new SeedVault(new MemBlobStore(), live);

        LinkedHashMap<Integer, Integer> snap = new LinkedHashMap<>();
        snap.put(0, 4);
        byte[] backup = backupOf(SEED, true, snap);   // even a 'trusted-in-file' backup restores UNTRUSTED

        vault.importVault(backup, PASS, PASS);

        // C1.1: restore is NOT auto-trusted.
        assertFalse("restore must NOT be trusted", vault.isKeyUsesTrusted());

        // Fail-safe gate throws while untrusted.
        try {
            vault.assertSigningAllowed();
            fail("signing must be blocked after a restore until attested");
        } catch (SeedVault.SigningNotAllowedException expected) { /* good */ }

        // Restore still MAX-reconciled the live counter up to the snapshot.
        assertEquals("restore reconciles the counter up to the snapshot", 4, live.currentUses(0));

        // C1.2: only the explicit attestation flips trusted → signing allowed.
        vault.attestKeyUsesUpToDate();
        assertTrue("after attestation, trusted", vault.isKeyUsesTrusted());
        vault.assertSigningAllowed();   // must NOT throw now
    }

    // =============================================================================================
    // C1: restore MAX-reconcile never LOWERS a higher live counter
    // =============================================================================================
    @Test
    public void importVault_neverLowersCounter() throws Exception {
        InMemoryKeyUses live = new InMemoryKeyUses();
        live.recordExternalUses(0, 10);   // this device already signed 10 times since the backup

        SeedVault vault = new SeedVault(new MemBlobStore(), live);

        LinkedHashMap<Integer, Integer> stale = new LinkedHashMap<>();
        stale.put(0, 4);                  // STALE backup at 4
        vault.importVault(backupOf(SEED, true, stale), PASS, PASS);

        assertEquals("stale restore keeps the higher live count", 10, live.currentUses(0));
        assertFalse("still untrusted regardless of counter", vault.isKeyUsesTrusted());
    }

    // =============================================================================================
    // C1: the static reconcile helper returns UNTRUSTED and carries every index (multi-index)
    // =============================================================================================
    @Test
    public void reconcileRestored_untrusted_multiIndex() {
        InMemoryKeyUses live = new InMemoryKeyUses();
        live.recordExternalUses(1, 3);    // a higher index already live

        LinkedHashMap<Integer, Integer> snap = new LinkedHashMap<>();
        snap.put(0, 7);
        snap.put(1, 1);                   // lower than live[1]=3 → must not lower
        VaultBlob restored = new VaultBlob(SEED, true, snap);

        VaultBlob out = SeedVault.reconcileRestored(restored, live);

        assertFalse("reconcileRestored returns UNTRUSTED", out.isKeyUsesTrusted());
        assertEquals("index 0 reconciled", Integer.valueOf(7), out.getKeyUses().get(0));
        assertEquals("index 1 kept the higher live value", Integer.valueOf(3), out.getKeyUses().get(1));
        assertEquals("live index 0 raised", 7, live.currentUses(0));
        assertEquals("live index 1 not lowered", 3, live.currentUses(1));
    }

    // =============================================================================================
    // createNew remains trusted (freshly generated seed, provably zero prior spends)
    // =============================================================================================
    @Test
    public void createNew_isTrusted() {
        SeedVault vault = new SeedVault(new MemBlobStore(), new InMemoryKeyUses());
        vault.createNew(SEED, PASS);
        assertTrue("a freshly generated seed is trusted", vault.isKeyUsesTrusted());
        vault.assertSigningAllowed();   // must not throw
    }

    // =============================================================================================
    // open(): wrong passphrase → false; unreadable/corrupt vault → VaultCorruptException
    // =============================================================================================
    @Test
    public void open_distinguishesWrongPassFromCorrupt() {
        MemBlobStore store = new MemBlobStore();
        // Seed a real vault.
        new SeedVault(store, new InMemoryKeyUses()).createNew(SEED, PASS);

        // A fresh session over the same store.
        SeedVault reopen = new SeedVault(store, new InMemoryKeyUses());
        assertFalse("wrong passphrase returns false (not an exception)", reopen.open("nope-wrong"));
        assertTrue("correct passphrase opens", reopen.open(PASS));

        // Corrupt the stored blob into something that isn't even decodable hex.
        MemBlobStore corrupt = new MemBlobStore();
        corrupt.write("0xNOTHEX!!");
        SeedVault broken = new SeedVault(corrupt, new InMemoryKeyUses());
        try {
            broken.open(PASS);
            fail("an unreadable vault must throw VaultCorruptException, not report 'wrong passphrase'");
        } catch (SeedVault.VaultCorruptException expected) { /* good */ }
    }

    // =============================================================================================
    // Multi-index snapshot: export enumerates EVERY known index (future-proof), not just index 0
    // =============================================================================================
    @Test
    public void exportSnapshot_enumeratesAllIndices() throws Exception {
        InMemoryKeyUses live = new InMemoryKeyUses();
        live.recordExternalUses(0, 2);
        live.recordExternalUses(5, 9);    // a higher index the live store knows about

        SeedVault vault = new SeedVault(new MemBlobStore(), live);
        vault.importSeed(SEED, PASS, true);   // trusted brand-new import; snapshot should carry 0 and 5

        byte[] exported = vault.exportBytes(PASS);
        VaultBlob back = VaultBlob.fromBytes(VaultCrypto.decrypt(PASS, exported));

        assertEquals("index 0 in snapshot", Integer.valueOf(2), back.getKeyUses().get(0));
        assertEquals("higher index 5 NOT dropped from snapshot", Integer.valueOf(9), back.getKeyUses().get(5));
    }
}
