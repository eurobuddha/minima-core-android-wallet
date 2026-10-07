package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.utils.Crypto;

/**
 * The wallet's full BASE address set — all {@link WalletCore#NUM_BASE_KEYS} (64) addresses, exactly
 * the default set a Minima node derives from the same seed (modifiers 0..63).
 *
 * <p>Deriving ONE public key builds a full TreeKey (64 WOTS keygens), so deriving all 64 takes
 * seconds-to-minutes on a phone. This class therefore persists the 64 PUBLIC keys (public data —
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

    public static final int SIZE = WalletCore.NUM_BASE_KEYS;

    private static final String PREFS = "addressbook";

    private final SharedPreferences mPrefs;
    private final WalletCore mWallet;

    /** 0x pubkey hex by index; null until derived. */
    private final String[] mPubKeys = new String[SIZE];
    private final String[] mAddr0x  = new String[SIZE];
    private final String[] mAddrMx  = new String[SIZE];
    private final Map<String, Integer> mIndexByAddr0x = new HashMap<>();

    /** Count of consecutively-derived indices from 0 — indices [0, derivedCount) are usable. */
    private volatile int mDerived = 0;

    /** Notified (on the derive thread) after each key lands; UI marshals itself. */
    public interface DeriveListener { void onProgress(int zDerived); }
    private volatile DeriveListener mListener;

    public void setDeriveListener(DeriveListener zListener) {
        mListener = zListener;
    }

    public AddressBook(Context zContext, WalletCore zWallet) {
        mWallet = zWallet;
        mPrefs  = zContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String fp = fingerprint();
        if (fp.equals(mPrefs.getString("seed", ""))) {
            for (int i = 0; i < SIZE; i++) {
                String pk = mPrefs.getString("pk." + i, null);
                if (pk == null) break;   // contiguous prefix only
                put(i, new MiniData(pk));
            }
        } else {
            // Different (or first) seed — start a fresh cache bound to this seed's fingerprint.
            mPrefs.edit().clear().putString("seed", fp).apply();
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
        while (n < SIZE && mPubKeys[n] != null) n++;
        mDerived = n;

        DeriveListener l = mListener;
        if (l != null) l.onProgress(n);
    }

    /**
     * Derive (and persist) every missing pubkey, in index order. EXPENSIVE on a cold cache — call
     * from a background thread. Also spot-checks one random already-cached index (see class doc).
     * Safe to call again at any time; a warm, verified cache returns quickly.
     */
    public void deriveMissing() {
        // Spot-check one random cached index by full re-derivation.
        if (mDerived > 0) {
            int probe = new Random().nextInt(mDerived);
            MiniData truth = mWallet.deriveTreeKey(probe).getPublicKey();
            if (!truth.to0xString().equals(mPubKeys[probe])) {
                // Corrupt cache — wipe and rebuild from scratch.
                synchronized (this) {
                    mPrefs.edit().clear().putString("seed", fingerprint()).apply();
                    for (int i = 0; i < SIZE; i++) { mPubKeys[i] = null; mAddr0x[i] = null; mAddrMx[i] = null; }
                    mIndexByAddr0x.clear();
                    mDerived = 0;
                }
            }
        }

        for (int i = 0; i < SIZE; i++) {
            if (mPubKeys[i] != null) continue;
            MiniData pk = mWallet.getPublicKey(i);   // expensive TreeKey build
            put(i, pk);
            mPrefs.edit().putString("pk." + i, pk.to0xString()).apply();
        }
    }

    /** True once all 64 base addresses are known. */
    public boolean isComplete() {
        return mDerived >= SIZE;
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
        return mAddrMx[zIndex];
    }

    /** The 0x.. form of base address {@code zIndex} (null if not yet derived). */
    public synchronized String addr0x(int zIndex) {
        return mAddr0x[zIndex];
    }
}
