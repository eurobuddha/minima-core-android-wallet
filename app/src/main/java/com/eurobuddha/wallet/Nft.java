package com.eurobuddha.wallet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/**
 * NFT / StateNFT read helpers over the BUNDLED json (org.minima.utils.json — what
 * {@code MainActivity.coins()} holds). Ported from the NFT wallet's StateNft, trimmed to display
 * concerns only: transfers here are built locally from parsed {@code StateVariable}s by
 * {@link TxnFactory#buildNftTransfer}, so none of the donor's command-string sanitisers apply.
 */
final class Nft {

    /** The two StateNFT token scripts (legacy + locked edition), as minted by statenft-suite. */
    private static final Pattern LEGACY_SCRIPT = Pattern.compile(
            "^IF SIGNEDBY\\((0x[0-9A-Fa-f]+)\\) THEN RETURN TRUE ENDIF RETURN VERIFYOUT\\(@INPUT GETOUTADDR\\(@INPUT\\) @AMOUNT @TOKENID TRUE\\)$");
    private static final Pattern LOCKED_SCRIPT = Pattern.compile(
            "^LET s=PREVSTATE\\(0\\) IF s EQ 0 AND SIGNEDBY\\((0x[0-9A-Fa-f]+)\\) THEN RETURN TRUE ENDIF RETURN SAMESTATE\\(0 [01]\\) AND VERIFYOUT\\(@INPUT GETOUTADDR\\(@INPUT\\) @AMOUNT @TOKENID TRUE\\)$");

    private Nft() {}

    static String str(JSONObject o, String k) {
        Object v = o == null ? null : o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static JSONObject obj(Object v) {
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    /** Token metadata parsed from a coin's bundled {@code token} node. */
    static class Meta {
        String name = "Token";
        String description = "";
        String mode = "";       // "embed" | "url" | "" (plain NFT)
        String base = "";
        String ext = ".png";
        String icon = "";
        String decimals = "";
        String script = "";
        boolean stateNft = false;
    }

    /** Parse the token descriptor riding on a coin ({@code coin.token}); name may be a plain
     *  string or the {name,url,description,mode,base,ext,...} object statenft-suite seals. */
    static Meta meta(JSONObject zTokenNode) {
        Meta m = new Meta();
        if (zTokenNode == null) return m;
        m.decimals = str(zTokenNode, "decimals");
        m.script   = str(zTokenNode, "script");
        m.stateNft = isStateNftScript(m.script);
        Object nameNode = zTokenNode.get("name");
        JSONObject name = obj(nameNode);
        if (name != null) {
            m.name        = firstNonEmpty(str(name, "name"), "Token");
            m.description = str(name, "description");
            m.mode        = str(name, "mode");
            m.base        = str(name, "base");
            m.ext         = firstNonEmpty(str(name, "ext"), ".png");
            m.icon        = firstNonEmpty(str(name, "url"), str(name, "icon"));
            if (m.mode.isEmpty() && !m.base.isEmpty()) m.mode = "url";
        } else if (nameNode != null) {
            m.name = String.valueOf(nameNode);
        }
        return m;
    }

    static boolean isStateNftScript(String zScript) {
        if (zScript == null) return false;
        return LOCKED_SCRIPT.matcher(zScript).matches() || LEGACY_SCRIPT.matcher(zScript).matches();
    }

    /**
     * True only for the LOCKED-edition script. An UNSTAMPED locked item (state 0 absent or "0")
     * keeps the creator bypass live — {@code PREVSTATE(0) EQ 0 AND SIGNEDBY(creator)} — so after a
     * transfer the creator could still reclaim the recipient's coin. Sends of such coins are
     * refused (same rule as the NFT wallet).
     */
    static boolean isLockedScript(String zScript) {
        return zScript != null && LOCKED_SCRIPT.matcher(zScript).matches();
    }

    /** True when this coin must NOT be sent: locked-edition script with no stamp sealed yet. */
    static boolean isUnstampedLocked(Meta zMeta, JSONObject zCoin) {
        if (zMeta == null || !isLockedScript(zMeta.script)) return false;
        String s0 = state(zCoin, 0);
        return s0 == null || "0".equals(s0);
    }

    /** NFT candidate = non-Minima token with 0 (or absent) decimals — the nftstudio rule. */
    static boolean isNftMeta(Meta m) {
        return m.decimals.isEmpty() || "0".equals(m.decimals);
    }

    /** Every state entry as {port, data}, both the array form and the {port:data} form. */
    static List<String[]> rawStateEntries(JSONObject zCoin) {
        List<String[]> out = new ArrayList<>();
        if (zCoin == null) return out;
        Object st = zCoin.get("state");
        if (st instanceof JSONArray) {
            for (Object o : (JSONArray) st) {
                JSONObject s = obj(o);
                if (s == null) continue;
                out.add(new String[]{ str(s, "port"), str(s, "data") });
            }
        } else if (st instanceof JSONObject) {
            for (Object e : ((JSONObject) st).entrySet()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) e;
                out.add(new String[]{ String.valueOf(me.getKey()), String.valueOf(me.getValue()) });
            }
        }
        return out;
    }

    /** One state port for interpretation (surrounding quotes are transport artefacts). */
    static String state(JSONObject zCoin, int zPort) {
        for (String[] e : rawStateEntries(zCoin)) {
            if (e[0].equals(String.valueOf(zPort))) {
                String v = e[1];
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    /** The sealed item index (state port 0), or -1 when unstamped / not a StateNFT item. */
    static int itemIndex(JSONObject zCoin) {
        String s0 = state(zCoin, 0);
        if (s0 == null) return -1;
        try { return Integer.parseInt(s0); } catch (NumberFormatException e) { return -1; }
    }

    /** The display image for one coin: embedded plate (state 1), else base+idx+ext, else icon. */
    static String imageUrl(Meta zMeta, int zIdx, JSONObject zCoin) {
        String embedded = state(zCoin, 1);
        if (embedded != null && embedded.startsWith("[") && embedded.endsWith("]")) {
            return dataUri(embedded.substring(1, embedded.length() - 1));
        }
        if (zMeta != null && !zMeta.base.isEmpty() && zIdx >= 0) {
            return zMeta.base + zIdx + zMeta.ext;
        }
        return IconResolver.resolve(zMeta == null ? "" : zMeta.icon);
    }

    /** data: URI for a sealed payload, mime sniffed from the magic bytes (WebP/JPEG/PNG/SVG). */
    static String dataUri(String zB64) {
        if (zB64 == null || zB64.isEmpty()) return "";
        return "data:" + mimeOf(zB64) + ";base64," + zB64;
    }

    private static String mimeOf(String zB64) {
        try {
            int take = Math.min(zB64.length(), 32);
            take -= take % 4;
            byte[] head = android.util.Base64.decode(zB64.substring(0, take), android.util.Base64.DEFAULT);
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "image/webp";
            }
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (head.length >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            String text = new String(head, StandardCharsets.UTF_8).trim().toLowerCase();
            if (text.startsWith("<svg") || text.startsWith("<?xml")) return "image/svg+xml";
        } catch (Throwable ignored) { }
        return "image/jpeg";
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }
}
