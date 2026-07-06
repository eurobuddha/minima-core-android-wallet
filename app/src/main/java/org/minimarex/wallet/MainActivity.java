package org.minimarex.wallet;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.utils.BIP39;
import org.minimarex.wallet.comms.NodeApi;
import org.minimarex.wallet.comms.NodeLink;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M3 home — the full self-custodial wallet: onboarding (generate / import seed + wallet passphrase),
 * unlock, and the tabbed UI (Balances / Send / Receive / Settings). The seed + WOTS keyuses now live in
 * the portable, seed-derived-encrypted {@link SeedVault} (M2's device-bound {@code SeedStore} is
 * retired), and Send/Split/Consolidate are wired through {@link TxnFactory} + {@link NodeLink}.
 */
public class MainActivity extends AppCompatActivity {

    public static final int TAB_BALANCES = 0;
    public static final int TAB_SEND     = 1;
    public static final int TAB_RECEIVE  = 2;
    public static final int TAB_SETTINGS = 3;

    // --- wallet core ---
    private WalletSession mSession;
    private PrefsKeyUses mKeyUses;
    private SeedVault mVault;
    private WalletCore mWallet;
    private TxnFactory mFactory;
    private String mAddr0x;
    private String mAddrMx;

    // --- transport ---
    private NodeLink mNode;
    private boolean mPaired = false;
    private boolean mTracked = false;

    // --- balances state ---
    private final List<TokenBalance> mBalances = new ArrayList<>();
    private final Map<String, TokenMeta> mMeta = new LinkedHashMap<>();
    private org.minima.utils.json.JSONArray mLastCoins = new org.minima.utils.json.JSONArray();
    private String mBlock = "";

    // --- views ---
    private TextView mBlockView;
    private LinearLayout mPairingBanner;
    private FrameLayout mContent;
    private TextView mTabBalances, mTabSend, mTabReceive, mTabSettings;
    private BalancesView mBalancesView;
    private SendView mSendView;
    private ReceiveView mReceiveView;
    private SettingsView mSettingsView;
    private int mTab = TAB_BALANCES;

    // --- ActivityResult launchers (must be registered in onCreate) ---
    private ActivityResultLauncher<ScanOptions> mScanLauncher;
    private ActivityResultLauncher<String> mCreateDocLauncher;
    private ActivityResultLauncher<String[]> mOpenDocLauncher;
    private Consumer<String> mScanCallback;
    private Consumer<Uri> mCreateDocCallback;
    private Consumer<Uri> mOpenDocCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);

        registerLaunchers();

        // The open-vault session is Application-scoped, so an unlock survives Activity recreate()/
        // background for the chosen auto-lock timeout (see WalletSession).
        mSession = WalletSession.get(this);
        mKeyUses = mSession.keyUses();
        mVault   = mSession.vault();

        if (!mVault.exists()) {
            showOnboarding();
        } else if (!mVault.isOpen() || mSession.shouldLockOnForeground(System.currentTimeMillis())) {
            // Locked, or the auto-lock timeout elapsed while backgrounded → require unlock.
            mSession.lock();
            showUnlock();
        } else {
            mSession.onHandledForeground(true);
            startMain();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Enforce the auto-lock timeout when returning to the foreground. Only acts on a real
        // return-from-background (WalletSession ignores config-change recreate); a foreground recreate
        // keeps the session open even in "Immediately" mode.
        if (mSession != null && mVault != null && mVault.isOpen()) {
            if (mSession.shouldLockOnForeground(System.currentTimeMillis())) {
                mSession.lock();
                showUnlock();
            } else {
                mSession.onHandledForeground(true);
            }
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // Keep the session alive while the user is actively interacting.
        if (mSession != null && mVault != null && mVault.isOpen()) mSession.touch();
    }

    private void registerLaunchers() {
        mScanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (mScanCallback != null && result != null && result.getContents() != null) {
                mScanCallback.accept(result.getContents());
            }
            mScanCallback = null;
        });
        mCreateDocLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (mCreateDocCallback != null && uri != null) mCreateDocCallback.accept(uri);
                    mCreateDocCallback = null;
                });
        mOpenDocLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (mOpenDocCallback != null && uri != null) mOpenDocCallback.accept(uri);
                    mOpenDocCallback = null;
                });
    }

    // =============================================================================================
    // Onboarding
    // =============================================================================================

    private void showOnboarding() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Minima Wallet"));
        col.addView(body("Self-custodial. Your seed and funds live on THIS device; the node only relays."));

        col.addView(primary("Create a new wallet", v -> onboardGenerate()));
        col.addView(secondary("Import an existing seed", v -> onboardImport()));
    }

    private void onboardGenerate() {
        final String phrase = BIP39.convertWordListToString(BIP39.getNewWordList()).trim();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Back up your seed"));
        col.addView(body("Write these words down and keep them safe. Anyone with them controls your funds. "
                + "You will also set a passphrase — you need BOTH the seed and the passphrase to restore."));

        TextView seed = new TextView(this);
        seed.setText(phrase);
        seed.setTypeface(Typeface.MONOSPACE);
        seed.setTextColor(Design.text());
        seed.setTextSize(15f);
        seed.setBackgroundColor(Design.surface());
        seed.setPadding(dp(14), dp(14), dp(14), dp(14));
        seed.setTextIsSelectable(true);
        col.addView(seed);

        col.addView(label("WALLET PASSPHRASE (min 8)"));
        final EditText p1 = password();
        col.addView(p1);
        col.addView(label("CONFIRM PASSPHRASE"));
        final EditText p2 = password();
        col.addView(p2);

        col.addView(primary("Continue", v -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 8) { toast("Passphrase too short (min 8)"); return; }
            if (!a.equals(b)) { toast("Passphrases do not match"); return; }
            // M2: do NOT complete setup yet — require the user to prove they backed up the seed.
            onboardVerifySeed(phrase, a);
        }));
    }

    /**
     * M2: seed-backup confirmation. Before a freshly generated wallet is considered set up (and can be
     * funded), the user must re-enter 3 randomly chosen words from the phrase — proving they actually
     * wrote it down. Only on success do we {@link SeedVault#createNew} and enter the wallet.
     */
    private void onboardVerifySeed(final String phrase, final String passphrase) {
        final String[] words = phrase.trim().split("\\s+");

        // Pick 3 distinct random word positions (ascending for a tidy prompt order).
        java.util.List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < words.length; i++) pool.add(i);
        java.util.Collections.shuffle(pool);
        final List<Integer> pick = new ArrayList<>(pool.subList(0, Math.min(3, words.length)));
        java.util.Collections.sort(pick);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Confirm your backup"));
        col.addView(body("Prove you wrote the seed down: enter the requested words. If you can't, go back "
                + "and write the whole phrase down again — it is the ONLY way to recover your funds."));

        final List<EditText> answers = new ArrayList<>();
        for (int idx : pick) {
            col.addView(label("WORD #" + (idx + 1)));
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setTextColor(Design.text());
            e.setHintTextColor(Design.dim());
            e.setBackgroundColor(Design.surface());
            e.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            e.setLayoutParams(lp);
            col.addView(e);
            answers.add(e);
        }

        col.addView(primary("Confirm & create wallet", v -> {
            for (int i = 0; i < pick.size(); i++) {
                String expected = words[pick.get(i)];
                String got = answers.get(i).getText().toString().trim();
                if (!got.equalsIgnoreCase(expected)) {
                    toast("Word #" + (pick.get(i) + 1) + " does not match — check your written copy");
                    return;
                }
            }
            mVault.createNew(phrase, passphrase);
            mSession.touch();
            startMain();
        }));

        col.addView(secondary("Show my seed again", v -> {
            TextView tv = new TextView(this);
            tv.setText(phrase);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(Design.text());
            tv.setTextSize(15f);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Your seed phrase")
                    .setMessage("Write these words down on paper. Do NOT screenshot or copy them.")
                    .setView(tv)
                    .setNegativeButton("Close", null)
                    .show();
        }));
    }

    private void onboardImport() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(32), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Import a seed"));
        col.addView(body("Paste a 24-word mnemonic OR any phrase of 16+ characters (web-wallet style). "
                + "Both derive the wallet the same way."));

        final EditText phrase = new EditText(this);
        phrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        phrase.setMinLines(2);
        phrase.setTextColor(Design.text());
        phrase.setHintTextColor(Design.dim());
        phrase.setHint("your seed phrase");
        phrase.setBackgroundColor(Design.surface());
        phrase.setPadding(dp(12), dp(12), dp(12), dp(12));
        col.addView(phrase);

        col.addView(label("WALLET PASSPHRASE (min 8)"));
        final EditText p1 = password();
        col.addView(p1);
        col.addView(label("CONFIRM PASSPHRASE"));
        final EditText p2 = password();
        col.addView(p2);

        final CheckBox brandNew = new CheckBox(this);
        brandNew.setText("This seed has NEVER signed a transaction (safe to start uses at 0)");
        brandNew.setTextColor(Design.text());
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.topMargin = dp(12); brandNew.setLayoutParams(cbp);
        col.addView(brandNew);
        col.addView(body("If you leave this unticked, sending stays BLOCKED until you confirm the seed is "
                + "new or restore a keyuses backup — this protects against reusing a one-time key."));

        col.addView(primary("Import wallet", v -> {
            String ph = phrase.getText().toString().trim();
            if (ph.length() < 16) { toast("Seed must be at least 16 characters"); return; }
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 8) { toast("Passphrase too short (min 8)"); return; }
            if (!a.equals(b)) { toast("Passphrases do not match"); return; }
            mVault.importSeed(ph, a, brandNew.isChecked());
            mSession.touch();
            startMain();
        }));
    }

    // =============================================================================================
    // Unlock
    // =============================================================================================

    private void showUnlock() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(60), dp(24), dp(24));
        sv.addView(col);
        setContentView(sv);
        applyInsets(sv);

        col.addView(bigTitle("Unlock wallet"));
        col.addView(body("Enter your wallet passphrase."));

        final EditText p = password();
        col.addView(p);
        col.addView(primary("Unlock", v -> {
            try {
                if (mVault.open(p.getText().toString())) {
                    onUnlocked();
                } else {
                    toast("Wrong passphrase");
                }
            } catch (SeedVault.VaultCorruptException corrupt) {
                // Right passphrase (or unreadable blob) but the vault could not be applied — distinct
                // from a wrong passphrase, so tell the user their vault is damaged (restore from backup).
                toast("Vault is damaged — restore from your encrypted backup");
            }
        }));

        // Biometric unlock is an OPTIONAL convenience — the typed passphrase above always remains the
        // fallback. Only shown when the user enabled it AND the hardware/enrolment is currently usable.
        if (BiometricUnlock.isEnabled(this) && BiometricUnlock.isAvailable(this)) {
            col.addView(secondary("Unlock with biometrics", v -> promptBiometricUnlock()));
        }
    }

    /** Kick off a biometric unlock; on success open the vault via the SAME path as a typed passphrase. */
    private void promptBiometricUnlock() {
        BiometricUnlock.unlock(this, new BiometricUnlock.UnlockCallback() {
            @Override public void onPassphrase(String passphrase) {
                try {
                    if (mVault.open(passphrase)) {
                        onUnlocked();
                    } else {
                        // The stored passphrase no longer opens the vault (e.g. it was changed without
                        // re-enrolling biometrics). Fall back to typing it.
                        toast("Biometric passphrase is out of date — unlock with your passphrase");
                    }
                } catch (SeedVault.VaultCorruptException corrupt) {
                    toast("Vault is damaged — restore from your encrypted backup");
                }
            }
            @Override public void onError(String message, boolean permanentlyInvalidated) {
                toast(message);
                if (permanentlyInvalidated) recreate();   // repaint unlock without the biometric button
            }
            @Override public void onCancelled() { /* user backed out — passphrase field stays available */ }
        });
    }

    /** Shared post-unlock step: mark the session active and enter the wallet. */
    private void onUnlocked() {
        mSession.touch();
        startMain();
    }

    // =============================================================================================
    // Main UI
    // =============================================================================================

    private void startMain() {
        mWallet  = new WalletCore(mVault.phrase(), mKeyUses);
        mFactory = new TxnFactory(mWallet);
        mAddr0x  = mWallet.getReceiveAddress().getAddressData().to0xString();
        mAddrMx  = mWallet.getReceiveAddress().getMinimaAddress();

        buildUi();

        mNode = new NodeLink(this, this::onPaired);
    }

    private void buildUi() {
        LinearLayout rootv = new LinearLayout(this);
        rootv.setOrientation(LinearLayout.VERTICAL);
        rootv.setBackgroundColor(Design.bg());

        rootv.addView(buildHeader(),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mPairingBanner = buildPairingBanner();
        mPairingBanner.setVisibility(View.GONE);
        rootv.addView(mPairingBanner,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        rootv.addView(buildTabBar(),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mContent = new FrameLayout(this);
        rootv.addView(mContent, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootv);
        applyInsets(rootv);

        mBalancesView = new BalancesView(this);
        mSendView     = new SendView(this);
        mReceiveView  = new ReceiveView(this);
        mSettingsView = new SettingsView(this);
        goToTab(TAB_BALANCES);
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(14), dp(16), dp(10));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Minima Wallet");
        title.setTextColor(Design.accent());
        title.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        title.setTextSize(17f);
        TextView sub = new TextView(this);
        sub.setText("SELF-CUSTODIAL");
        sub.setTextColor(Design.dim());
        sub.setTextSize(9f);
        sub.setLetterSpacing(0.15f);
        brand.addView(title);
        brand.addView(sub);
        h.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mBlockView = new TextView(this);
        mBlockView.setText("#—");
        mBlockView.setTextColor(Design.dim());
        mBlockView.setTextSize(13f);
        mBlockView.setTypeface(Design.typeface());
        h.addView(mBlockView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return h;
    }

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.accentSoft());
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView t1 = new TextView(this);
        t1.setText("This wallet is not enabled yet");
        t1.setTextColor(Design.accent());
        t1.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t1.setTextSize(14f);
        b.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("Open Minima Core → Apps and enable “Minima Wallet” (no admin needed), then come back.");
        t2.setTextColor(Design.dim());
        t2.setTextSize(12f);
        t2.setPadding(0, dp(3), 0, 0);
        b.addView(t2);
        return b;
    }

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Design.bg());
        mTabBalances = tab("BALANCES", TAB_BALANCES);
        mTabSend     = tab("SEND", TAB_SEND);
        mTabReceive  = tab("RECEIVE", TAB_RECEIVE);
        mTabSettings = tab("SETTINGS", TAB_SETTINGS);
        bar.addView(mTabBalances, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabSend, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabReceive, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mTabSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    private TextView tab(String label, int idx) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(11f);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setOnClickListener(v -> goToTab(idx));
        return t;
    }

    public void goToTab(int zTab) {
        mTab = zTab;
        mContent.removeAllViews();
        BaseView view;
        switch (zTab) {
            case TAB_SEND:     view = mSendView; break;
            case TAB_RECEIVE:  view = mReceiveView; break;
            case TAB_SETTINGS: view = mSettingsView; break;
            default:           view = mBalancesView;
        }
        mContent.addView(view.getRoot(),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.refresh();
        view.onShown();
        paintTabs();
    }

    private void paintTabs() {
        TextView[] tabs = {mTabBalances, mTabSend, mTabReceive, mTabSettings};
        int[] ids = {TAB_BALANCES, TAB_SEND, TAB_RECEIVE, TAB_SETTINGS};
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = mTab == ids[i];
            tabs[i].setTextColor(sel ? Design.accent() : Design.dim());
            tabs[i].setBackgroundColor(sel ? Design.surface() : Design.bg());
        }
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    // =============================================================================================
    // Pairing + node loads
    // =============================================================================================

    private void onPaired(boolean zEnabled) {
        mPaired = zEnabled;
        if (mPairingBanner != null) mPairingBanner.setVisibility(zEnabled ? View.GONE : View.VISIBLE);
        if (!zEnabled) return;

        if (!mTracked) {
            mTracked = true;
            // TRACK our full RETURN SIGNEDBY(...) script so a regular node indexes our coins as
            // relevant (idempotent; forward-only). Balances then read `coins relevant:true`.
            mNode.trackScript(mWallet.getScript(0), new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) { loadEverything(); }
                @Override public void onError(String message)   { loadEverything(); }
            });
        } else {
            loadEverything();
        }
        loadBlock();
    }

    private void loadEverything() {
        mNode.tokens(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { parseTokens(json); loadCoins(); }
            @Override public void onError(String message)   { loadCoins(); }
        });
    }

    private void loadCoins() {
        mNode.coins(mAddr0x, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                rebuildBalances(json);
                refreshCurrent();
            }
            @Override public void onError(String message) {
                if (!NodeApi.ERR_NOT_ENABLED.equals(message)) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadBlock() {
        mNode.block(new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", r.optString("blocknumber", ""));
                    if (!b.isEmpty()) { mBlock = b; if (mBlockView != null) mBlockView.setText("#" + b); }
                }
            }
            @Override public void onError(String message) { }
        });
    }

    private void parseTokens(JSONObject json) {
        mMeta.clear();
        JSONArray arr = json.optJSONArray("response");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject tok = arr.optJSONObject(i);
            if (tok == null) continue;
            String tokenid = tok.optString("tokenid", "");
            if (tokenid.isEmpty()) continue;
            mMeta.put(tokenid, TokenMeta.parse(tok, tokenid));
        }
    }

    /** Aggregate coins by tokenid AND retain the raw coins array for coin selection in Send. */
    private void rebuildBalances(JSONObject json) {
        mBalances.clear();

        org.minima.utils.json.JSONArray mcoins = new org.minima.utils.json.JSONArray();
        JSONArray arr = json.optJSONArray("response");
        if (arr != null) {
            org.minima.utils.json.parser.JSONParser parser = new org.minima.utils.json.parser.JSONParser();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                try {
                    mcoins.add(parser.parse(c.toString()));
                } catch (Exception ignore) { }
            }
        }
        mLastCoins = mcoins;   // authoritative raw coins for Send/Split/Consolidate selection

        for (CoinAggregator.Agg agg : CoinAggregator.aggregate(mcoins)) {
            TokenBalance tb = new TokenBalance();
            tb.tokenid = agg.tokenid;
            TokenMeta meta = mMeta.get(agg.tokenid);
            if (meta == null) meta = TokenMeta.parse(null, agg.tokenid);
            tb.meta = meta;
            tb.name = meta.name;
            String amt = Util.tidyAmount(agg.displayTotal.toString());
            tb.confirmed   = amt;
            tb.sendable    = amt;
            tb.unconfirmed = "0";
            tb.total       = amt;
            tb.coins       = agg.count;
            mBalances.add(tb);
        }
    }

    private void refreshCurrent() {
        switch (mTab) {
            case TAB_SEND:     if (mSendView != null) mSendView.refresh(); break;
            case TAB_SETTINGS: if (mSettingsView != null) mSettingsView.refresh(); break;
            default:           if (mBalancesView != null) mBalancesView.refresh();
        }
    }

    // =============================================================================================
    // Accessors used by the views
    // =============================================================================================

    public WalletCore wallet()   { return mWallet; }
    public SeedVault vault()     { return mVault; }
    public WalletSession session() { return mSession; }
    public TxnFactory factory()  { return mFactory; }
    public KeyUses keyUses()     { return mKeyUses; }
    public NodeLink node()       { return mNode; }
    public boolean isPaired()    { return mPaired; }
    public List<TokenBalance> balances() { return mBalances; }
    public org.minima.utils.json.JSONArray coins() { return mLastCoins; }
    public String defaultAddress() { return mAddrMx; }
    public String address0x()      { return mAddr0x; }
    public String blockLabel()     { return mBlock.isEmpty() ? "—" : "#" + mBlock; }
    public String circulatingSupply() { return ""; }

    /** Re-pull tokens + coins + balances from the node and refresh the active tab. */
    public void reload() {
        if (mPaired && mNode != null) loadEverything();
    }

    // ---- ActivityResult bridges for the views ----

    public void scanQr(Consumer<String> onResult) {
        mScanCallback = onResult;
        ScanOptions opts = new ScanOptions();
        opts.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        opts.setPrompt("Scan a Minima address");
        opts.setBeepEnabled(false);
        opts.setOrientationLocked(false);
        mScanLauncher.launch(opts);
    }

    public void createBackupFile(String suggestedName, Consumer<Uri> onPicked) {
        mCreateDocCallback = onPicked;
        mCreateDocLauncher.launch(suggestedName);
    }

    public void openBackupFile(Consumer<Uri> onPicked) {
        mOpenDocCallback = onPicked;
        mOpenDocLauncher.launch(new String[]{"*/*"});
    }

    public void writeBytes(Uri uri, byte[] bytes) throws Exception {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new IllegalStateException("Cannot open file for writing");
            os.write(bytes);
            os.flush();
        }
    }

    public byte[] readBytes(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalStateException("Cannot open file for reading");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // =============================================================================================
    // Small onboarding view builders
    // =============================================================================================

    private TextView bigTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Design.accent());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(24f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Design.dim());
        t.setTextSize(13f);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private EditText password() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        e.setLayoutParams(lp);
        return e;
    }

    private Button primary(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Design.onAccent());
        b.setBackgroundColor(Design.accent());
        b.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button secondary(String label, View.OnClickListener l) {
        Button b = primary(label, l);
        b.setTextColor(Design.accent());
        b.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.topMargin = dp(10);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mNode != null) mNode.onDestroy();
    }
}
