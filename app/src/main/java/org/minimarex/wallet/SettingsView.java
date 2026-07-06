package org.minimarex.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.minimarex.wallet.comms.NodeApi;
import org.minimarex.wallet.comms.NodeLink;

/**
 * Settings tab: the explicit keyuses meter, seed reveal (auth-gated), encrypted backup / restore, the
 * fail-safe controls when keyuses is untrusted, and node / debug info.
 */
public class SettingsView extends BaseView {

    private final LinearLayout container;

    public SettingsView(MainActivity a) {
        super(a, makeRoot(a));
        ScrollView sv = (ScrollView) root;
        container = (LinearLayout) sv.getChildAt(0);
        refresh();
    }

    private static View makeRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 24));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        return sv;
    }

    @Override
    public void refresh() {
        container.removeAllViews();

        // --- Security / keyuses -------------------------------------------------------------------
        container.addView(sectionTitle("SECURITY"));

        int used = act.keyUses().currentUses(0);
        container.addView(kv("Signatures used", used + " / " + Util.WOTS_MAX_USES));
        container.addView(hint("Each send consumes one one-time (WOTS) signature. Never reuse one — a "
                + "reuse can expose the key. This counter is committed before every signature and "
                + "survives reinstall/restore (always taking the MAX)."));

        boolean trusted = act.vault().isKeyUsesTrusted();
        if (!trusted) {
            container.addView(warnBox("Keyuses record is NOT trusted",
                    "The number of one-time (WOTS) signatures this seed has already used is unknown — "
                  + "either it was imported bare, or it was just restored from a backup that may be stale. "
                  + "Sending is BLOCKED until you resolve this. Reusing a one-time key EXPOSES it and can "
                  + "lose your funds, so choose the option that is TRUE for you:"));
            container.addView(dangerButton("This seed is BRAND-NEW (never signed anywhere)", v -> confirmBrandNew()));
            container.addView(dangerButton("This restored backup is my MOST-RECENT state", v -> attestRestored()));
            container.addView(outlineButton("Restore keyuses backup…", v -> restore()));
        }

        // --- Seed backup --------------------------------------------------------------------------
        container.addView(sectionTitle("SEED & BACKUP"));
        container.addView(outlineButton("Reveal seed phrase", v -> revealSeed()));
        container.addView(outlineButton("Export encrypted backup…", v -> exportBackup()));
        container.addView(outlineButton("Restore from backup…", v -> restore()));
        container.addView(hint("The backup is a small file holding the seed + keyuses, encrypted with a "
                + "passphrase you choose. It decrypts on any device — and it is your ONLY seed-recovery "
                + "path: the seed vault is deliberately NOT included in Android cloud / Auto Backup. Export "
                + "it, remember the passphrase, and keep it safe — without it, a lost or wiped device means "
                + "lost funds."));

        // --- Unlock & session ---------------------------------------------------------------------
        container.addView(sectionTitle("UNLOCK & SESSION"));

        // Biometric unlock toggle (convenience only — the passphrase is always the fallback).
        boolean bioAvailable = BiometricUnlock.isAvailable(act);
        boolean bioEnabled   = BiometricUnlock.isEnabled(act);
        if (!bioAvailable && !bioEnabled) {
            container.addView(kv("Biometric unlock", "Not available on this device"));
        } else if (bioEnabled) {
            container.addView(kv("Biometric unlock", "Enabled"));
            container.addView(outlineButton("Disable biometric unlock", v -> disableBiometric()));
        } else {
            container.addView(outlineButton("Enable biometric unlock", v -> enableBiometric()));
        }
        container.addView(hint("Biometric unlock stores your passphrase in this device's secure hardware, "
                + "released only after a fingerprint / face / device-PIN check. It is a convenience only: "
                + "your passphrase always still works, and a new or wiped device still needs it (or your "
                + "encrypted backup). Changing your fingerprints disables it automatically."));

        // Auto-lock timeout.
        long timeout = act.session().timeoutMs();
        int tIdx = SessionPolicy.indexOfTimeout(timeout);
        container.addView(kv("Auto-lock", SessionPolicy.TIMEOUT_LABELS[tIdx]));
        container.addView(outlineButton("Change auto-lock timeout", v -> pickAutoLock()));
        container.addView(hint("How long the wallet stays unlocked in the background before it locks and "
                + "asks for your passphrase again. \"Immediately\" locks every time you leave the app."));

        // Change passphrase.
        container.addView(outlineButton("Change unlock passphrase", v -> changePassphrase()));

        // Lock now.
        container.addView(outlineButton("Lock now", v -> {
            act.session().lock();
            act.recreate();
        }));

        // --- Node ---------------------------------------------------------------------------------
        container.addView(sectionTitle("NODE"));
        container.addView(kv("Pairing", act.isPaired() ? "Enabled (non-admin)" : "Not enabled"));
        container.addView(kv("Chain tip", act.blockLabel()));
        container.addView(kvMono("Our address", act.address0x()));

        // --- Restore historic coins ---------------------------------------------------------------
        addRestoreHistoricSection();

        // --- Diagnostics --------------------------------------------------------------------------
        container.addView(sectionTitle("DIAGNOSTICS"));
        container.addView(outlineButton("Run diagnostics", v -> runDiagnostics()));
        container.addView(hint("Makes the wallet↔node interaction visible: pairing state, our receive "
                + "address, node status (height / sync / MegaMMR), and the exact coin queries we run. "
                + "Read-only (no admin commands). Shows every raw response so a stuck/empty balance can be "
                + "pinpointed. Copy or screenshot the report to share it."));

        // --- App ----------------------------------------------------------------------------------
        container.addView(sectionTitle("APP"));
        container.addView(kv("Design", Design.label()));
        container.addView(outlineButton("Toggle Light / Dark", v -> {
            Design.set(act, Design.next());
            act.recreate();
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void confirmBrandNew() {
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Confirm brand-new seed")
                .setMessage("Only confirm if this seed has NEVER signed a transaction on any device or "
                        + "wallet. If it has, continuing risks reusing a one-time key and losing funds.\n\n"
                        + "Mark keyuses as trusted, starting from 0?")
                .setPositiveButton("Yes, it is brand-new", (d, w) -> {
                    act.vault().confirmBrandNewSeed();
                    Toast.makeText(act, "Signing enabled (uses start at 0)", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * C1: the LOUD attestation that turns a just-restored (untrusted) vault back into a signing wallet.
     * A backup can be STALE — if the seed signed anything after the backup was made, the restored WOTS
     * uses counter is too low, and signing now would REUSE a one-time key and expose it. Only the user
     * knows whether the backup is their most-recent state, so we make them say so explicitly (or sweep).
     */
    private void attestRestored() {
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Is this backup up to date?")
                .setMessage("Each send uses a one-time (WOTS) signature that must NEVER be used twice — "
                        + "reusing one exposes the key and can lose your funds.\n\n"
                        + "This wallet just restored a keyuses backup. If you have signed ANY transaction "
                        + "from this seed since that backup was made (in this app or any other wallet), the "
                        + "restored count is too low and signing now would reuse a key.\n\n"
                        + "Only continue if you have NOT signed anything from this seed since this backup "
                        + "was made. If you are unsure, do NOT enable signing — instead sweep all funds to "
                        + "a brand-new wallet from your other/most-recent wallet.")
                .setPositiveButton("I have NOT signed since this backup — enable signing", (d, w) -> {
                    act.vault().attestKeyUsesUpToDate();
                    Toast.makeText(act, "Signing enabled from the restored counter", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void revealSeed() {
        // Auth gate: re-enter the wallet passphrase before the seed is shown.
        final EditText pass = passwordField("Wallet passphrase");
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Reveal seed phrase")
                .setMessage("Anyone with this phrase controls your funds. Make sure no one is watching.")
                .setView(wrap(pass))
                .setPositiveButton("Reveal", (d, w) -> {
                    // Confirm the passphrase actually unlocks the stored vault (defence-in-depth).
                    try {
                        if (!act.vault().open(pass.getText().toString())) {
                            Toast.makeText(act, "Wrong passphrase", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (SeedVault.VaultCorruptException corrupt) {
                        Toast.makeText(act, "Vault is damaged: " + corrupt.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSeedDialog(act.vault().phrase());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSeedDialog(String phrase) {
        TextView tv = new TextView(act);
        tv.setText(phrase);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Design.text());
        tv.setTextSize(15f);
        tv.setPadding(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
        // SECURITY (M2): the seed is deliberately NOT copyable to the system clipboard — a clipboard
        // entry is readable by other apps and easily leaked. Write it down by hand. The text is
        // selectable for the user's own on-screen reading only.
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Your seed phrase")
                .setMessage("Write these words down on paper. Do NOT screenshot or copy them.")
                .setView(wrap(tv))
                .setNegativeButton("Close", null)
                .show();
    }

    private void exportBackup() {
        final EditText pass = passwordField("Backup passphrase (min 12)");
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Export encrypted backup")
                .setMessage("Choose a passphrase to protect the backup file. You will need it (and only "
                        + "it) to restore on any device.\n\nThis file holds your seed. Its security is only "
                        + "as strong as this passphrase — use at least 12 characters, ideally several "
                        + "random words. A weak passphrase can be brute-forced to steal your funds.")
                .setView(wrap(pass))
                .setPositiveButton("Choose file…", (d, w) -> {
                    String p = pass.getText().toString();
                    if (p.length() < 12) {
                        Toast.makeText(act, "Passphrase too short (min 12)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    act.createBackupFile("minima-wallet-backup.mvlt", uri -> {
                        try {
                            byte[] bytes = act.vault().exportBytes(p);
                            act.writeBytes(uri, bytes);
                            Toast.makeText(act, "Backup saved (" + bytes.length + " bytes)", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(act, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restore() {
        act.openBackupFile(uri -> {
            try {
                final byte[] bytes = act.readBytes(uri);
                final EditText pass = passwordField("Backup passphrase");
                new androidx.appcompat.app.AlertDialog.Builder(act)
                        .setTitle("Restore from backup")
                        .setMessage("Enter the passphrase that protects this backup file.")
                        .setView(wrap(pass))
                        .setPositiveButton("Restore", (d, w) -> doRestore(bytes, pass.getText().toString()))
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(act, "Could not read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void doRestore(byte[] bytes, String filePass) {
        try {
            // Preview first (validates the passphrase before committing).
            VaultBlob preview = SeedVault.peekImport(bytes, filePass);
            // Reuse the same passphrase as the new wallet passphrase for simplicity.
            act.vault().importVault(bytes, filePass, filePass);
            // C1: a restore does NOT auto-enable signing (the backup could be stale). Signing stays
            // blocked until the user makes the explicit up-to-date attestation in Settings → Security.
            Toast.makeText(act, "Restored. Keyuses reconciled (MAX). Sending stays BLOCKED until you "
                    + "attest this backup is up to date (Settings → Security).", Toast.LENGTH_LONG).show();
            // Re-derive from the (possibly different) restored seed.
            act.recreate();
        } catch (Exception e) {
            Toast.makeText(act, "Restore failed: wrong passphrase or corrupt file", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unlock & session actions
    // ---------------------------------------------------------------------------------------------

    /**
     * Enable biometric unlock. We require the passphrase here (defence-in-depth) and, crucially, obtain
     * the plaintext passphrase to hand to the Keystore — the vault holds it in memory only while open, so
     * re-confirming it is the clean way to capture it. On success it is stored encrypted by a
     * user-auth-required Keystore key.
     */
    private void enableBiometric() {
        final EditText pass = passwordField("Wallet passphrase");
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Enable biometric unlock")
                .setMessage("Confirm your wallet passphrase. It will be stored in this device's secure "
                        + "hardware and released only after a fingerprint / face / device-PIN check. Your "
                        + "passphrase always still works as a fallback.")
                .setView(wrap(pass))
                .setPositiveButton("Continue", (d, w) -> {
                    final String p = pass.getText().toString();
                    // Verify the passphrase actually opens the vault before storing it.
                    try {
                        if (!act.vault().open(p)) {
                            Toast.makeText(act, "Wrong passphrase", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (SeedVault.VaultCorruptException e) {
                        Toast.makeText(act, "Vault is damaged: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    BiometricUnlock.enable(act, p, new BiometricUnlock.Callback() {
                        @Override public void onSuccess() {
                            Toast.makeText(act, "Biometric unlock enabled", Toast.LENGTH_LONG).show();
                            refresh();
                        }
                        @Override public void onError(String message, boolean invalidated) {
                            Toast.makeText(act, "Could not enable: " + message, Toast.LENGTH_LONG).show();
                            refresh();
                        }
                        @Override public void onCancelled() { refresh(); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disableBiometric() {
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Disable biometric unlock")
                .setMessage("This wipes the securely stored passphrase from this device. You will unlock "
                        + "with your passphrase. Continue?")
                .setPositiveButton("Disable", (d, w) -> {
                    BiometricUnlock.disable(act);
                    Toast.makeText(act, "Biometric unlock disabled", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickAutoLock() {
        int current = SessionPolicy.indexOfTimeout(act.session().timeoutMs());
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Auto-lock timeout")
                .setSingleChoiceItems(SessionPolicy.TIMEOUT_LABELS, current, (d, which) -> {
                    act.session().setTimeoutMs(SessionPolicy.TIMEOUT_CHOICES_MS[which]);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Change the wallet passphrase: require the current passphrase, then a new one (min 12, matching the
     * export minimum) entered twice. Re-encrypts the vault atomically (verify-before-replace) and, if
     * biometric unlock is enabled, re-stores the new passphrase behind the Keystore key.
     */
    private void changePassphrase() {
        final EditText cur = passwordField("Current passphrase");
        final EditText n1  = passwordField("New passphrase (min 12)");
        final EditText n2  = passwordField("Confirm new passphrase");

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 8));
        box.addView(cur, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        box.addView(n1, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        box.addView(n2, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));

        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Change unlock passphrase")
                .setMessage("Your seed and keyuses stay the same — only the passphrase that encrypts them "
                        + "on this device changes. Remember the new one: it is required to unlock and, with "
                        + "your seed, to restore.")
                .setView(box)
                .setPositiveButton("Change", (d, w) -> {
                    String c = cur.getText().toString();
                    String a = n1.getText().toString();
                    String b = n2.getText().toString();
                    if (!SessionPolicy.passphraseMeetsMin(a)) {
                        Toast.makeText(act, "New passphrase too short (min " + SessionPolicy.MIN_PASSPHRASE
                                + ")", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(act, "New passphrases do not match", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Verify the CURRENT passphrase by opening the vault with it.
                    try {
                        if (!act.vault().open(c)) {
                            Toast.makeText(act, "Current passphrase is wrong", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (SeedVault.VaultCorruptException e) {
                        Toast.makeText(act, "Vault is damaged: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        act.vault().changePassphrase(a);
                    } catch (Exception e) {
                        Toast.makeText(act, "Change failed (vault unchanged): " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // If biometric is on, re-store the NEW passphrase behind the Keystore key.
                    if (BiometricUnlock.isEnabled(act)) {
                        BiometricUnlock.enable(act, a, new BiometricUnlock.Callback() {
                            @Override public void onSuccess() {
                                Toast.makeText(act, "Passphrase changed; biometric updated",
                                        Toast.LENGTH_LONG).show();
                            }
                            @Override public void onError(String message, boolean invalidated) {
                                // Vault passphrase already changed successfully; just the biometric copy
                                // is now stale — disable it so the user re-enables with the new one.
                                BiometricUnlock.disable(act);
                                Toast.makeText(act, "Passphrase changed. Biometric unlock was turned off — "
                                        + "re-enable it if you want it.", Toast.LENGTH_LONG).show();
                                refresh();
                            }
                            @Override public void onCancelled() {
                                BiometricUnlock.disable(act);
                                Toast.makeText(act, "Passphrase changed. Biometric unlock was turned off.",
                                        Toast.LENGTH_LONG).show();
                                refresh();
                            }
                        });
                    } else {
                        Toast.makeText(act, "Passphrase changed", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics — read-only wallet↔node visibility (non-admin commands only)
    // ---------------------------------------------------------------------------------------------

    private static final String DIAG_TAG = "walletdiag";

    private StringBuilder mDiag;
    private TextView mDiagText;
    private ScrollView mDiagScroll;

    /**
     * Run a fixed sequence of READ-ONLY node queries and show every raw result in a scrollable,
     * selectable dialog (and mirror each line to logcat under {@code walletdiag}). This exists to make
     * the wallet↔node interaction visible when a balance stays empty while funds are confirmed on-chain.
     * No admin command is ever issued.
     */
    private void runDiagnostics() {
        mDiag = new StringBuilder();

        // Scrollable, selectable report body.
        mDiagText = new TextView(act);
        mDiagText.setTextIsSelectable(true);
        mDiagText.setTypeface(Typeface.MONOSPACE);
        mDiagText.setTextColor(Design.text());
        mDiagText.setTextSize(11f);
        mDiagText.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));

        mDiagScroll = new ScrollView(act);
        mDiagScroll.addView(mDiagText);

        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Wallet diagnostics")
                .setView(mDiagScroll)
                .setPositiveButton("Copy", null)   // wired below so it does NOT dismiss
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
        // Keep the dialog open when Copy is tapped (results may still be arriving).
        Button copy = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        if (copy != null) copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                // The diagnostic text is NOT the seed — clipboard is acceptable here.
                cm.setPrimaryClip(ClipData.newPlainText("Minima wallet diagnostics", mDiag.toString()));
                Toast.makeText(act, "Diagnostics copied", Toast.LENGTH_SHORT).show();
            }
        });

        // Step 1 — pairing state (synchronous).
        diagLine("=== Minima Wallet diagnostics ===");
        diagLine("");
        diagLine("[1] PAIRING");
        boolean paired = act.isPaired();
        diagLine("  isPaired(): " + paired);
        diagLine("  " + (paired
                ? "App is enabled in Minima Core (non-admin)."
                : "App is NOT enabled. Enable \"Minima Wallet\" in Minima Core -> Apps, then re-run. "
                + "Commands below will likely return " + NodeApi.ERR_NOT_ENABLED + "."));
        diagLine("");

        // Step 2 — our address (both forms), from the same derivation the wallet uses.
        diagLine("[2] OUR RECEIVE ADDRESS");
        final String addr0x = act.address0x();
        diagLine("  Mx: " + act.defaultAddress());
        diagLine("  0x: " + addr0x);
        diagLine("  (node commands below query the 0x form)");
        diagLine("");

        final NodeLink node = act.node();
        if (node == null) {
            diagLine("[!] Node link is not available (wallet not started). Aborting.");
            return;
        }

        // Steps 3-6 run sequentially, chaining the callbacks.
        diagStatus(node, addr0x);
    }

    /** Step 3 — status: block height, sync state, MegaMMR flag + raw. */
    private void diagStatus(final NodeLink node, final String addr0x) {
        diagLine("[3] status");
        node.raw("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String height = r.optString("length", r.optString("block", "?"));
                    JSONObject chain = r.optJSONObject("chain");
                    if (chain != null) {
                        height = chain.optString("block", chain.optString("length", height));
                    }
                    diagLine("  status: " + json.optBoolean("status", false));
                    diagLine("  block/height: " + height);
                    diagLine("  syncing/root: " + r.optString("root", r.optString("syncing", "?")));
                    // MegaMMR flag can appear at response root or nested — surface whatever we find.
                    diagLine("  megammr flag: " + megammrFlag(r));
                }
                diagRaw(json);
                diagScripts(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagScripts(node, addr0x);
            }
        });
    }

    /** Step 4 — scripts address:<ours> (is our RETURN SIGNEDBY script TRACKED / relevant?). */
    private void diagScripts(final NodeLink node, final String addr0x) {
        final String cmd = "scripts address:" + addr0x;
        diagLine("[4] " + cmd);
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                diagLine("  (a hit here == our script is tracked; empty == run the wallet once while "
                        + "paired so it calls newscript trackall:true)");
                diagRaw(json);
                diagCheckAddress(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagCheckAddress(node, addr0x);
            }
        });
    }

    /** Step 5 — checkaddress address:<ours> (confirm the address is valid / recognised). */
    private void diagCheckAddress(final NodeLink node, final String addr0x) {
        final String cmd = "checkaddress address:" + addr0x;
        diagLine("[5] " + cmd);
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                diagRaw(json);
                diagCoins(node, addr0x);
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagCoins(node, addr0x);
            }
        });
    }

    /** Step 6 — coins relevant:true address:<ours> (the exact query the wallet uses for balances). */
    private void diagCoins(final NodeLink node, final String addr0x) {
        final String cmd = "coins relevant:true address:" + addr0x;
        diagLine("[6] " + cmd);
        node.raw(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                diagLine("  status: " + json.optBoolean("status", false));
                diagCoinsSummary(json);
                diagRaw(json);
                diagLine("");
                diagLine("=== end of diagnostics ===");
            }
            @Override public void onError(String message) {
                diagErr(message);
                diagLine("");
                diagLine("=== end of diagnostics ===");
            }
        });
    }

    /** Best-effort MegaMMR flag lookup across the likely field names / nesting. */
    private static String megammrFlag(JSONObject response) {
        if (response.has("megammr"))   return String.valueOf(response.opt("megammr"));
        if (response.has("MEGAMMR"))   return String.valueOf(response.opt("MEGAMMR"));
        JSONObject mmr = response.optJSONObject("megammr");
        if (mmr != null)               return mmr.toString();
        // Some builds nest it under memory/txpow; note absence explicitly rather than silently.
        return "(field not present — see raw below)";
    }

    /** Append a coins-response one-liner: status + coin count + first coin's amount/coinid. */
    private void diagCoinsSummary(JSONObject json) {
        diagLine("  status: " + json.optBoolean("status", false));
        org.json.JSONArray arr = json.optJSONArray("response");
        int count = arr == null ? 0 : arr.length();
        diagLine("  coin count: " + count);
        if (arr != null && count > 0) {
            JSONObject c0 = arr.optJSONObject(0);
            if (c0 != null) {
                diagLine("  first coin: amount=" + c0.optString("amount", "?")
                        + " tokenid=" + c0.optString("tokenid", "0x00")
                        + " coinid=" + c0.optString("coinid", "?"));
            }
        }
        if (!json.optBoolean("status", false)) {
            String err = json.optString("error", "");
            if (!err.isEmpty()) diagLine("  error: " + err);
        }
    }

    /** Append a step's raw JSON, pretty-printed where possible. */
    private void diagRaw(JSONObject json) {
        String raw;
        try {
            raw = json.toString(2);
        } catch (Throwable t) {
            raw = json.toString();
        }
        diagLine("  raw: " + raw);
        diagLine("");
    }

    private void diagErr(String message) {
        diagLine("  ERROR: " + message);
        diagLine("");
    }

    /** Append one line to the report: log it, add to the buffer, update the dialog, scroll to bottom. */
    private void diagLine(String line) {
        Log.i(DIAG_TAG, line);
        if (mDiag == null) return;
        mDiag.append(line).append('\n');
        if (mDiagText != null) mDiagText.setText(mDiag.toString());
        if (mDiagScroll != null) mDiagScroll.post(() -> mDiagScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------------------------------------
    // Restore historic coins — recover coins funded BEFORE we began tracking (non-admin)
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact WHOLE-NODE re-sync command. Shown as copyable text ONLY — the wallet is non-admin and
     * MUST NEVER issue this (it re-syncs the entire node from the public MegaMMR). Display-only.
     */
    private static final String MEGAMMR_RESYNC_CMD =
            "megammrsync action:resync host:megammr.minima.global:9001";

    /**
     * Build the RESTORE HISTORIC COINS section. Coins funded at our address BEFORE the wallet ran
     * {@code newscript} (forward-only tracking) are never back-filled, so a regular node can't see them.
     *
     * <p>Primary path (works now, non-admin): paste a {@code coinexport} blob from a MegaMMR node; we
     * {@code coincheck} it against the LOCAL tip MMR, and on a valid proof {@code coinimport track:true}
     * to insert + mark it spendable, then refresh balances. Advanced path: the whole-node
     * {@code megammrsync} re-sync shown as copyable text only (admin — the wallet never runs it).
     */
    private void addRestoreHistoricSection() {
        container.addView(sectionTitle("RESTORE HISTORIC COINS"));
        container.addView(hint("Coins sent to your address BEFORE the wallet started tracking it aren't "
                + "indexed by a regular node. Import them here using a coin export from a MegaMMR node."));

        // --- Primary path: import a coin export (non-admin, works now) ---------------------------
        final EditText blob = multilineField("Paste the coinexport blob (0x…)");
        container.addView(blob);
        container.addView(outlineButton("Import coin export", v -> importCoinExport(blob)));
        container.addView(hint("How to get this: on a MegaMMR node run  "
                + "coins address:<your Mx addr> megammr:true  to find the coinid, then  "
                + "coinexport coinid:<id>  and paste the result here."));
        container.addView(kvMono("Your address (Mx)", act.defaultAddress()));

        // --- Advanced path: whole-node re-sync (DISPLAY ONLY — the wallet never runs it) ---------
        container.addView(hint("Advanced — full node re-sync (optional):"));
        container.addView(monoBox(MEGAMMR_RESYNC_CMD));
        container.addView(outlineButton("Copy re-sync command",
                v -> copyToClipboard("Minima megammrsync command", MEGAMMR_RESYNC_CMD)));
        container.addView(warnBox("Advanced — needs admin, run it on your node yourself",
                "Run this on your minimaCore node (needs admin). It re-syncs your whole node from the "
              + "public MegaMMR and recovers ALL historic coins at your tracked address. The wallet can't "
              + "run it (non-admin) and won't run it for you."));
    }

    private StringBuilder mImportLog;
    private TextView mImportText;

    /**
     * Import flow: validate then insert an exported coin proof. Sanitises the pasted blob (strips
     * whitespace/newlines; requires a {@code 0x…} hex string), then {@code coincheck} → (if valid)
     * {@code coinimport track:true} → refresh balances. Every step's result (and any node error) is shown
     * verbatim in a selectable dialog. Both node commands are non-admin.
     */
    private void importCoinExport(final EditText input) {
        // Sanitise: a paste often carries surrounding whitespace / newlines.
        final String hex = input.getText().toString().replaceAll("\\s+", "");
        if (hex.isEmpty()) {
            Toast.makeText(act, "Paste a coin export blob first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hex.matches("0[xX][0-9a-fA-F]+")) {
            Toast.makeText(act, "That doesn't look like a coin export — expected a 0x… hex string",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final NodeLink node = act.node();
        if (node == null) {
            Toast.makeText(act, "Node link not available — open the wallet while paired first",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Selectable, scrollable results dialog; each step appends its outcome.
        mImportLog = new StringBuilder();
        mImportText = new TextView(act);
        mImportText.setTextIsSelectable(true);
        mImportText.setTypeface(Typeface.MONOSPACE);
        mImportText.setTextColor(Design.text());
        mImportText.setTextSize(11f);
        mImportText.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        ScrollView scroll = new ScrollView(act);
        scroll.addView(mImportText);
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Restore historic coin")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();

        // Step 1 — coincheck: validate the proof against the LOCAL tip MMR (read-only, non-admin).
        importLog("[1/2] coincheck — validating the proof against your node's MMR…");
        node.coinCheck(hex, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                boolean valid = json.optBoolean("status", false);
                importLog("  valid: " + valid);
                String err = json.optString("error", "");
                if (!err.isEmpty()) importLog("  node: " + err);
                importRaw(json);
                if (!valid) {
                    importLog("");
                    importLog("Not imported — the proof did not validate. The coin may already be spent, "
                            + "or its proof anchor is outside the ~256-block window. Re-export it from a "
                            + "MegaMMR node and try again.");
                    return;
                }
                // Step 2 — coinimport track:true: insert + mark relevant/spendable (non-admin).
                importLog("");
                importLog("[2/2] coinimport track:true — inserting the coin and tracking it…");
                node.coinImport(hex, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json2) {
                        boolean imported = json2.optBoolean("status", false);
                        importLog("  imported: " + imported);
                        String e2 = json2.optString("error", "");
                        if (!e2.isEmpty()) importLog("  node: " + e2);
                        importRaw(json2);
                        if (imported) {
                            importLog("");
                            importLog("Done. Refreshing balances…");
                            // Same path MainActivity uses to re-pull tokens + coins + balances.
                            act.reload();
                        }
                    }
                    @Override public void onError(String message) {
                        importLog("  ERROR: " + message);
                    }
                });
            }
            @Override public void onError(String message) {
                importLog("  ERROR: " + message);
            }
        });
    }

    /** Append one line to the import results dialog. */
    private void importLog(String line) {
        if (mImportLog == null) return;
        mImportLog.append(line).append('\n');
        if (mImportText != null) mImportText.setText(mImportLog.toString());
    }

    /** Append a step's raw JSON, pretty-printed where possible. */
    private void importRaw(JSONObject json) {
        String raw;
        try { raw = json.toString(2); } catch (Throwable t) { raw = json.toString(); }
        importLog("  raw: " + raw);
    }

    /** Copy plain text to the system clipboard (never used for the seed — safe for commands only). */
    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Small view builders
    // ---------------------------------------------------------------------------------------------

    private TextView sectionTitle(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setPadding(0, dp(act, 18), 0, dp(act, 8));
        return t;
    }

    private View kv(String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(act, 5), 0, dp(act, 5));
        TextView l = new TextView(act);
        l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(14f);
        TextView v = new TextView(act);
        v.setText(value); v.setTextColor(Design.text()); v.setTextSize(14f); v.setGravity(Gravity.END);
        v.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroupWC(), 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroupWC(), 1.4f));
        return r;
    }

    private View kvMono(String label, String value) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(act, 5), 0, dp(act, 5));
        TextView l = new TextView(act);
        l.setText(label); l.setTextColor(Design.dim()); l.setTextSize(14f);
        TextView v = new TextView(act);
        v.setText(value); v.setTextColor(Design.text()); v.setTextSize(11f);
        v.setTypeface(Typeface.MONOSPACE); v.setTextIsSelectable(true);
        col.addView(l);
        col.addView(v);
        return col;
    }

    private TextView hint(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(Design.dim2()); t.setTextSize(11.5f);
        t.setPadding(0, dp(act, 6), 0, dp(act, 4));
        return t;
    }

    private View warnBox(String title, String body) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.redSoft());
        b.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8); b.setLayoutParams(lp);
        TextView t = new TextView(act);
        t.setText(title); t.setTextColor(Design.red()); t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(14f);
        TextView d = new TextView(act);
        d.setText(body); d.setTextColor(Design.text()); d.setTextSize(12f); d.setPadding(0, dp(act, 4), 0, 0);
        b.addView(t); b.addView(d);
        return b;
    }

    private Button outlineButton(String label, View.OnClickListener l) {
        Button btn = new Button(act);
        btn.setAllCaps(false);
        btn.setText(label);
        btn.setTextColor(Design.accent());
        btn.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8); btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    private Button dangerButton(String label, View.OnClickListener l) {
        Button btn = outlineButton(label, l);
        btn.setTextColor(Design.onAccent());
        btn.setBackgroundColor(Design.red());
        return btn;
    }

    private EditText passwordField(String hint) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        return e;
    }

    /** A multi-line, monospace text input (for pasting a long hex blob). Editable == selectable. */
    private EditText multilineField(String hint) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(3);
        e.setMaxLines(6);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(12f);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8);
        e.setLayoutParams(lp);
        return e;
    }

    /** A selectable, monospace box for showing a command / hex string the user can copy. */
    private TextView monoBox(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(Design.text());
        t.setTextSize(12f);
        t.setBackgroundColor(Design.surface());
        t.setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC());
        lp.topMargin = dp(act, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private View wrap(View v) {
        LinearLayout w = new LinearLayout(act);
        w.setPadding(dp(act, 20), dp(act, 8), dp(act, 20), dp(act, 8));
        w.addView(v, new LinearLayout.LayoutParams(ViewGroupMP(), ViewGroupWC()));
        return w;
    }

    private static int ViewGroupMP() { return LinearLayout.LayoutParams.MATCH_PARENT; }
    private static int ViewGroupWC() { return LinearLayout.LayoutParams.WRAP_CONTENT; }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
