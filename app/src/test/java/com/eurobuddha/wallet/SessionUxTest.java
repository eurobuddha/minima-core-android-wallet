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
 * Pure-JVM verification of the M4 unlock/session UX layer that is testable off-device:
 * <ul>
 *   <li><b>Change passphrase</b> — re-encrypt round-trips: the OLD passphrase opens before and FAILS
 *       after; the NEW passphrase FAILS before and opens after; seed, trust flag and keyuses snapshot
 *       are carried over unchanged; and the change is atomic (a locked vault refuses).</li>
 *   <li><b>Auto-lock timeout logic</b> — {@link SessionPolicy#shouldLock} across Immediately / timed /
 *       boundary cases.</li>
 *   <li><b>Passphrase minimum</b> — {@link SessionPolicy#passphraseMeetsMin}.</li>
 * </ul>
 * The biometric + Keystore path is Android-framework only and is documented as device-tested.
 */
public class SessionUxTest {

    private static final String SEED = "minima wallet portable seed phrase for the session ux test";
    private static final String OLD  = "old correct horse battery";
    private static final String NEW  = "new staple battery horse correct";

    /** In-memory {@link KeyUses}: same MAX / reserve-before-sign semantics as PrefsKeyUses. */
    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> mUses = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = mUses.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); mUses.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { mUses.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new LinkedHashMap<>(mUses); }
    }

    /** In-memory {@link SeedVault.BlobStore}. */
    static final class MemBlobStore implements SeedVault.BlobStore {
        private String mHex;
        public boolean contains() { return mHex != null; }
        public String read() { return mHex; }
        public void write(String hex) { mHex = hex; }
    }

    // =============================================================================================
    // Change passphrase: OLD decrypts before, NEW decrypts after, OLD fails after
    // =============================================================================================
    @Test
    public void changePassphrase_reEncryptRoundTrips() {
        MemBlobStore store = new MemBlobStore();
        SeedVault v = new SeedVault(store, new InMemoryKeyUses());
        v.createNew(SEED, OLD);

        // BEFORE: a fresh session opens with OLD, not with NEW.
        assertTrue("OLD opens before change", new SeedVault(store, new InMemoryKeyUses()).open(OLD));
        assertFalse("NEW does not open before change", new SeedVault(store, new InMemoryKeyUses()).open(NEW));

        // Rotate the passphrase on the open vault.
        v.changePassphrase(NEW);

        // AFTER: OLD fails, NEW opens, and everything else is preserved.
        assertFalse("OLD fails after change", new SeedVault(store, new InMemoryKeyUses()).open(OLD));

        SeedVault reopened = new SeedVault(store, new InMemoryKeyUses());
        assertTrue("NEW opens after change", reopened.open(NEW));
        assertEquals("seed preserved", SEED, reopened.phrase());
        assertTrue("trust flag preserved (createNew was trusted)", reopened.isKeyUsesTrusted());
    }

    // =============================================================================================
    // Change passphrase preserves the keyuses snapshot (MAX-reconciled on reopen)
    // =============================================================================================
    @Test
    public void changePassphrase_preservesKeyusesSnapshot() {
        MemBlobStore store = new MemBlobStore();
        InMemoryKeyUses live = new InMemoryKeyUses();
        live.recordExternalUses(0, 5);
        live.recordExternalUses(3, 9);

        SeedVault v = new SeedVault(store, live);
        v.importSeed(SEED, OLD, true);   // trusted brand-new; snapshot carries indices 0 and 3
        v.changePassphrase(NEW);

        // Reopen with NEW into a FRESH live counter; the snapshot must reconcile it back up.
        InMemoryKeyUses fresh = new InMemoryKeyUses();
        SeedVault reopened = new SeedVault(store, fresh);
        assertTrue(reopened.open(NEW));
        assertEquals("index 0 preserved through re-encrypt", 5, fresh.currentUses(0));
        assertEquals("higher index 3 preserved through re-encrypt", 9, fresh.currentUses(3));
    }

    // =============================================================================================
    // Change passphrase refuses on a locked vault (atomic: nothing is written)
    // =============================================================================================
    @Test
    public void changePassphrase_refusesWhenLocked() {
        MemBlobStore store = new MemBlobStore();
        new SeedVault(store, new InMemoryKeyUses()).createNew(SEED, OLD);

        SeedVault locked = new SeedVault(store, new InMemoryKeyUses());   // not opened
        try {
            locked.changePassphrase(NEW);
            fail("changePassphrase must require an open vault");
        } catch (IllegalStateException expected) { /* good */ }

        // The stored blob is untouched: OLD still opens.
        assertTrue("locked change left the vault intact", new SeedVault(store, new InMemoryKeyUses()).open(OLD));
    }

    // =============================================================================================
    // Auto-lock timeout logic
    // =============================================================================================
    @Test
    public void shouldLock_immediatelyAlwaysLocks() {
        assertTrue(SessionPolicy.shouldLock(1000L, 1000L, SessionPolicy.IMMEDIATELY_MS));
        assertTrue(SessionPolicy.shouldLock(1000L, 1001L, SessionPolicy.IMMEDIATELY_MS));
    }

    @Test
    public void shouldLock_timedBoundary() {
        long t = 5L * 60L * 1000L;   // 5 min
        long last = 10_000L;
        assertFalse("within timeout stays open", SessionPolicy.shouldLock(last, last + t, t));
        assertTrue("just past timeout locks", SessionPolicy.shouldLock(last, last + t + 1, t));
        assertFalse("well within stays open", SessionPolicy.shouldLock(last, last + 1000L, t));
    }

    @Test
    public void indexOfTimeout_defaultsToFiveMinutes() {
        assertEquals(2, SessionPolicy.indexOfTimeout(SessionPolicy.DEFAULT_TIMEOUT_MS));
        assertEquals(0, SessionPolicy.indexOfTimeout(SessionPolicy.IMMEDIATELY_MS));
        // Unknown value falls back to the default's index.
        assertEquals(2, SessionPolicy.indexOfTimeout(1234567L));
    }

    @Test
    public void passphraseMeetsMin_enforcesTwelve() {
        assertEquals(12, SessionPolicy.MIN_PASSPHRASE);
        assertFalse(SessionPolicy.passphraseMeetsMin(null));
        assertFalse(SessionPolicy.passphraseMeetsMin("short"));
        assertFalse(SessionPolicy.passphraseMeetsMin("elevenchars"));   // 11
        assertTrue(SessionPolicy.passphraseMeetsMin("twelvecharss"));   // 12
        assertTrue(SessionPolicy.passphraseMeetsMin("a much longer passphrase"));
    }
}
