package com.eurobuddha.wallet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Pure-JVM verification of M3's fund-critical security core (no Android). Proves exactly the four
 * properties the milestone requires:
 *
 * <ol>
 *   <li><b>Round-trip</b> — encrypt(seed+keyuses, passphrase) → decrypt → identical seed + keyuses.</li>
 *   <li><b>Portability</b> — a blob produced by one "instance" decrypts on a FRESH instance given only
 *       the passphrase (no device secret involved), i.e. it is NOT Keystore-bound.</li>
 *   <li><b>Keyuses MAX-reconcile never lowers</b> — restoring an older snapshot into a higher live
 *       counter keeps the higher value.</li>
 *   <li><b>Fail-safe</b> — a bare-seed import (trusted=false) is flagged so signing can be blocked.</li>
 * </ol>
 */
public class VaultSecurityTest {

    /** In-memory {@link KeyUses}: same MAX / reserve-before-sign semantics as PrefsKeyUses. */
    static final class InMemoryKeyUses implements KeyUses {
        private final Map<Integer, Integer> mUses = new HashMap<>();
        public synchronized int currentUses(int i) { Integer v = mUses.get(i); return v == null ? 0 : v; }
        public synchronized int reserveNextUse(int i) { int n = currentUses(i); mUses.put(i, n + 1); return n; }
        public synchronized void recordExternalUses(int i, int u) { mUses.put(i, Math.max(currentUses(i), u)); }
        public synchronized Map<Integer, Integer> snapshotAllUses() { return new LinkedHashMap<>(mUses); }
    }

    private static final String SEED = "minima wallet portable seed phrase for the vault test";
    private static final String PASS = "correct horse battery staple";

    // =============================================================================================
    // 1. Round-trip: seed + keyuses survive encrypt→decrypt byte-identically
    // =============================================================================================
    @Test
    public void roundTrip_seedAndKeyusesIdentical() throws Exception {
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 7);
        uses.put(1, 3);
        VaultBlob blob = new VaultBlob(SEED, true, uses);

        byte[] plain = blob.toBytes();
        byte[] container = VaultCrypto.encrypt(PASS, plain);

        byte[] decrypted = VaultCrypto.decrypt(PASS, container);
        assertArrayEquals("plaintext round-trips byte-identically", plain, decrypted);

        VaultBlob back = VaultBlob.fromBytes(decrypted);
        assertEquals("seed phrase identical", SEED, back.getPhrase());
        assertTrue("trusted flag identical", back.isKeyUsesTrusted());
        assertEquals("keyuses[0] identical", Integer.valueOf(7), back.getKeyUses().get(0));
        assertEquals("keyuses[1] identical", Integer.valueOf(3), back.getKeyUses().get(1));

        System.out.println("=== M3 VERIFY (1): round-trip ===");
        System.out.println("container bytes : " + container.length);
        System.out.println("seed back       : " + back.getPhrase());
        System.out.println("keyuses back    : " + back.getKeyUses());
    }

    // =============================================================================================
    // 2. Portability: a blob encrypted by one "instance" opens on a fresh one with only the passphrase
    // =============================================================================================
    @Test
    public void portability_freshInstanceDecryptsWithPassphraseOnly() throws Exception {
        // "Device A": create + encrypt.
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>();
        uses.put(0, 42);
        byte[] container = VaultCrypto.encrypt(PASS, new VaultBlob(SEED, true, uses).toBytes());

        // "Device B": a brand-new process holding ONLY the container bytes + the passphrase — no
        // Keystore, no device key, no shared state. (Static methods carry no instance state, so this
        // simulates a fresh install / different phone: only the passphrase decrypts it.)
        byte[] decrypted = VaultCrypto.decrypt(PASS, container);
        VaultBlob back = VaultBlob.fromBytes(decrypted);

        assertEquals("portable seed recovered on 'new device'", SEED, back.getPhrase());
        assertEquals("portable keyuses recovered", Integer.valueOf(42), back.getKeyUses().get(0));

        // And the wrong passphrase fails cleanly (no plaintext leak).
        try {
            VaultCrypto.decrypt("wrong passphrase", container);
            fail("wrong passphrase must not decrypt");
        } catch (VaultCrypto.BadPassphraseException expected) { /* good */ }

        System.out.println("=== M3 VERIFY (2): portability ===");
        System.out.println("recovered on new instance with passphrase only: seed + keyuses OK");
        System.out.println("wrong passphrase rejected: OK (NOT Keystore-bound)");
    }

    // =============================================================================================
    // 2b. Determinism of the KDF: same passphrase+salt+iters → identical key across "devices"
    // =============================================================================================
    @Test
    public void kdf_isDeterministicAcrossInvocations() throws Exception {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] k1 = VaultCrypto.pbkdf2HmacSha256("pw".getBytes(StandardCharsets.UTF_8), salt, 5000, 32);
        byte[] k2 = VaultCrypto.pbkdf2HmacSha256("pw".getBytes(StandardCharsets.UTF_8), salt, 5000, 32);
        assertArrayEquals("PBKDF2 deterministic", k1, k2);
        byte[] kDiff = VaultCrypto.pbkdf2HmacSha256("pw2".getBytes(StandardCharsets.UTF_8), salt, 5000, 32);
        assertNotEquals("different passphrase → different key", hex(k1), hex(kDiff));
        assertEquals("PBKDF2 output length", 32, k1.length);
    }

    // =============================================================================================
    // 3. Keyuses MAX-reconcile never lowers the live counter
    // =============================================================================================
    @Test
    public void reconcile_neverLowersCounter() throws Exception {
        InMemoryKeyUses live = new InMemoryKeyUses();
        // Live counter has advanced to 10 (ten txns signed on this device since the backup was taken).
        live.recordExternalUses(0, 10);

        // A STALE backup snapshot taken earlier at uses=4.
        LinkedHashMap<Integer, Integer> stale = new LinkedHashMap<>();
        stale.put(0, 4);
        VaultBlob staleBlob = new VaultBlob(SEED, true, stale);

        // Restoring the stale snapshot MUST NOT lower 10 → 4.
        staleBlob.reconcileInto(live);
        assertEquals("stale restore keeps the higher live count", 10, live.currentUses(0));

        // A NEWER backup snapshot (uses=25) raises it.
        LinkedHashMap<Integer, Integer> newer = new LinkedHashMap<>();
        newer.put(0, 25);
        newer.put(1, 2);
        new VaultBlob(SEED, true, newer).reconcileInto(live);
        assertEquals("newer restore raises the counter", 25, live.currentUses(0));
        assertEquals("newer restore adds a new key index", 2, live.currentUses(1));

        System.out.println("=== M3 VERIFY (3): keyuses MAX-reconcile ===");
        System.out.println("stale(4) into live(10) -> " + 10 + " (no lower); newer(25) -> "
                + live.currentUses(0));
    }

    // =============================================================================================
    // 4. Fail-safe: a bare-seed import is flagged untrusted
    // =============================================================================================
    @Test
    public void failSafe_bareSeedImportIsUntrusted() throws Exception {
        // Bare-seed import: no keyuses history at all → trusted=false.
        VaultBlob bare = new VaultBlob(SEED, false, new LinkedHashMap<>());
        assertFalse("bare-seed import is NOT trusted", bare.isKeyUsesTrusted());

        // Round-trips preserving the untrusted flag.
        byte[] container = VaultCrypto.encrypt(PASS, bare.toBytes());
        VaultBlob back = VaultBlob.fromBytes(VaultCrypto.decrypt(PASS, container));
        assertFalse("untrusted flag survives encrypt/decrypt", back.isKeyUsesTrusted());

        // A generated seed (or a confirmed brand-new seed) IS trusted at uses=0.
        VaultBlob fresh = new VaultBlob(SEED, true, new LinkedHashMap<>());
        assertTrue("generated / confirmed seed is trusted", fresh.isKeyUsesTrusted());

        // Flipping trusted (user confirms 'brand new') preserves everything else.
        VaultBlob confirmed = bare.withTrusted(true);
        assertTrue("after user confirmation, trusted", confirmed.isKeyUsesTrusted());
        assertEquals("seed unchanged by confirmation", SEED, confirmed.getPhrase());

        System.out.println("=== M3 VERIFY (4): fail-safe ===");
        System.out.println("bare import trusted=" + bare.isKeyUsesTrusted()
                + "  generated trusted=" + fresh.isKeyUsesTrusted()
                + "  confirmed trusted=" + confirmed.isKeyUsesTrusted());
    }

    // =============================================================================================
    // 5. Tamper detection: any bit-flip in the container fails authentication
    // =============================================================================================
    @Test
    public void tamper_isRejected() throws Exception {
        byte[] container = VaultCrypto.encrypt(PASS, new VaultBlob(SEED, true, new LinkedHashMap<>()).toBytes());
        // Flip a byte in the ciphertext body.
        byte[] tampered = container.clone();
        tampered[tampered.length - 1] ^= 0x01;
        try {
            VaultCrypto.decrypt(PASS, tampered);
            fail("tampered ciphertext must be rejected");
        } catch (VaultCrypto.BadPassphraseException expected) { /* good */ }

        // Flip a byte in the header (iteration count) — AAD covers it.
        byte[] tampered2 = container.clone();
        tampered2[6] ^= 0x01;   // inside the 4-byte iteration field
        try {
            VaultCrypto.decrypt(PASS, tampered2);
            fail("tampered header must be rejected");
        } catch (VaultCrypto.BadPassphraseException expected) { /* good */ }

        System.out.println("=== M3 VERIFY (5): tamper detection === ciphertext + header tampering rejected");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
