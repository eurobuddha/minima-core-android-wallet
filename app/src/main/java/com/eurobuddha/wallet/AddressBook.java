package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.utils.Crypto;

/**
 * The wallet's BASE address set — {@link #size()} addresses (modifiers 0..size-1), exactly the set a
 * Minima node derives from the same seed with {@code keys:<size>} (default
 * {@link WalletCore#NUM_BASE_KEYS} = 64).
 *
 * <p>The size is chosen at onboarding ({@link #setPendingKeyCount}) and can only GROW afterwards
 * ({@link #setSize}) — shrinking would hide coins at the dropped indices. It is persisted next to
 * the pubkey cache, keyed to the seed fingerprint.
 *
 * <p>Deriving ONE public key builds a full TreeKey (64 WOTS keygens), so deriving all of them takes
 * seconds-to-minutes on a phone. This class therefore persists the PUBLIC keys (public data —
 * never the seed) in SharedPreferences, keyed to a one-way fingerprint of the base seed, so every
 * launch after the first is instant. Derived pubkeys are also preloaded into {@link WalletCore}'s
 * in-memory cache, making {@code getAddress(i)} / TxnFactory's per-input address validation cheap.
 *
 * <p><b>Integrity:</b> a tampered/corrupted cache cannot leak funds (signing always re-derives from
 * the seed) but could display a receive address this wallet cannot spend. {@link #deriveMissing}
 * therefore spot-checks one RANDOM cached index per call by full re-derivation and, on mismatch,
 * clears the cache and re-derives everything.
 */
public class AddressBook {

    /** The Minima node default — what a seed resync derives when {@code keys:} is not given. */
    public static final int DEFAULT_SIZE = WalletCore.NUM_BASE_KEYS;
    /** Hard ceiling: each key is seconds of WOTS keygen on a phone. */
    public static final int MAX_SIZE = 1024;

    private static final String PREFS = "addressbook";
    private static final String K_KEYS = "keys";

    private final SharedPreferences mPrefs;
    private final WalletCore mWallet;

    private volatile int mSize;

    /** 0x pubkey hex by index; null until derived. */
    private String[] mPubKeys;
    private String[] mAddr0x;
    private String[] mAddrMx;
    private final Map<String, Integer> mIndexByAddr0x = new HashMap<>();

    /** Count of consecutively-derived indices from 0 — indices [0, derivedCount) are usable. */
    private volatile int mDerived = 0;

    /** Notified (on the derive thread) after each key lands; UI marshals itself. */
    public interface DeriveListener { void onProgress(int zDerived); }
    private volatile DeriveListener mListener;

    public void setDeriveListener(DeriveListener zListener) {
        mListener = zListener;
    }

    /**
     * Onboarding: record the key count the NEXT wallet should derive, before its seed exists. The
     * constructor carries it across the seed-fingerprint reset.
     */
    public static void setPendingKeyCount(Context zContext, int zKeys) {
        zContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(K_KEYS, clamp(zKeys)).apply();
    }

    private static int clamp(int zKeys) {
        return Math.max(1, Math.min(MAX_SIZE, zKeys));
    }

    public AddressBook(Context zContext, WalletCore zWallet) {
        mWallet = zWallet;
        mPrefs  = zContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        mSize = clamp(mPrefs.getInt(K_KEYS, DEFAULT_SIZE));
        mPubKeys = new String[mSize];
        mAddr0x  = new String[mSize];
        mAddrMx  = new String[mSize];

        String fp = fingerprint();
        if (fp.equals(mPrefs.getString("seed", ""))) {
            for (int i = 0; i < mSize; i++) {
                String pk = mPrefs.getString("pk." + i, null);
                if (pk == null) break;   // contiguous prefix only
                put(i, new MiniData(pk));
            }
        } else {
            // Different (or first) seed — start a fresh cache bound to this seed's fingerprint,
            // keeping the key count onboarding just chose.
            mPrefs.edit().clear().putString("seed", fp).putInt(K_KEYS, mSize).apply();
        }
    }

    /** One-way seed fingerprint (hash of the base seed) binding the cache to the current seed. */
    private String fingerprint() {
        return Crypto.getInstance().hashObject(mWallet.getBaseSeed()).to0xString();
    }

    /** Register a pubkey for an index: preload WalletCore, derive both address forms, index them. */
    private synchronized void put(int zIndex, MiniData zPubKey) {
        mWallet.preloadPublicKey(zIndex, zPubKey);
        Address addr = mWallet.getAddress(zIndex);   // cheap — pubkey is now cached
        mPubKeys[zIndex] = zPubKey.to0xString();
        mAddr0x[zIndex]  = addr.getAddressData().to0xString();
        mAddrMx[zIndex]  = addr.getMinimaAddress();
        mIndexByAddr0x.put(mAddr0x[zIndex], zIndex);

        int n = 0;
        while (n < mSize && mPubKeys[n] != null) n++;
        mDerived = n;

        DeriveListener l = mListener;
        if (l != null) l.onProgress(n);
    }

    /**
     * Derive (and persist) every missing pubkey, in index order. EXPENSIVE on a cold cache — call
     * from a background thread. Also spot-checks one random already-cached index (see class doc).
     * Safe to call again at any time; a warm, verified cache returns quickly.
     *
     * @return how many keys were derived by THIS call (0 on a warm cache).
     */
    public int deriveMissing() {
        // Spot-check one random cached index by full re-derivation.
        if (mDerived > 0) {
            int probe = new Random().nextInt(mDerived);
            MiniData truth = mWallet.deriveTreeKey(probe).getPublicKey();
            if (!truth.to0xString().equals(mPubKeys[probe])) {
                // Corrupt cache — wipe and rebuild from scratch.
                synchronized (this) {
                    mPrefs.edit().clear().putString("seed", fingerprint()).putInt(K_KEYS, mSize).apply();
                    Arrays.fill(mPubKeys, null); Arrays.fill(mAddr0x, null); Arrays.fill(mAddrMx, null);
                    mIndexByAddr0x.clear();
                    mDerived = 0;
                }
            }
        }

        int derived = 0;
        for (int i = 0; i < mSize; i++) {   // re-reads mSize: a grow mid-run is picked up
            if (mPubKeys[i] != null) continue;
            MiniData pk = mWallet.getPublicKey(i);   // expensive TreeKey build
            put(i, pk);
            mPrefs.edit().putString("pk." + i, pk.to0xString()).apply();
            derived++;
        }
        return derived;
    }

    /** How many base addresses this wallet has (derived or pending). */
    public int size() {
        return mSize;
    }

    /**
     * Grow the address set to {@code zSize} (persisted). Never shrinks — dropping an index would hide
     * any coins sitting at it. Call {@link #deriveMissing} afterwards (background thread).
     */
    public synchronized void setSize(int zSize) {
        int n = clamp(zSize);
        if (n <= mSize) return;
        mPubKeys = Arrays.copyOf(mPubKeys, n);
        mAddr0x  = Arrays.copyOf(mAddr0x, n);
        mAddrMx  = Arrays.copyOf(mAddrMx, n);
        mSize = n;
        mPrefs.edit().putInt(K_KEYS, n).apply();
    }

    /** True once all base addresses are known. */
    public boolean isComplete() {
        return mDerived >= mSize;
    }

    /** How many addresses (from index 0, contiguous) are usable right now. */
    public int derivedCount() {
        return mDerived;
    }

    /** The key index owning this 0x address, or null if it is not one of our (derived) addresses. */
    public synchronized Integer keyIndexFor(String zAddr0x) {
        return mIndexByAddr0x.get(zAddr0x);
    }

    /** The Mx.. form of base address {@code zIndex} (null if not yet derived). */
    public synchronized String addrMx(int zIndex) {
        return zIndex < mSize ? mAddrMx[zIndex] : null;
    }

    /** The 0x.. form of base address {@code zIndex} (null if not yet derived). */
    public synchronized String addr0x(int zIndex) {
        return zIndex < mSize ? mAddr0x[zIndex] : null;
    }
}
