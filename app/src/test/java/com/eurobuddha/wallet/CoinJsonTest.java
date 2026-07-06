package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.minima.objects.Coin;
import org.minima.objects.StateVariable;
import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.base.MiniString;
import org.minima.objects.mmr.MMREntryNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

/**
 * Pure-JVM verification for M2's two integration points:
 *  1. {@link TxnFactory#fromCoinJson} reconstructs an input {@link Coin} that serializes
 *     BYTE-IDENTICALLY to the on-chain coin (native + custom token), incl. state, so the signed
 *     transaction id and the node's MMR proof can never diverge.
 *  2. {@link CoinAggregator} groups a mixed coins response by tokenid with correct raw/display totals
 *     and counts.
 *
 * The coins JSON is produced by the node's own {@code Coin.toJSON()} then round-tripped through the
 * bundled {@code JSONParser} — i.e. exactly the string shape the {@code coins} command returns.
 */
public class CoinJsonTest {

    /** Serialize a Coin to its consensus bytes (== what Transaction hashes for the txid). */
    private static byte[] coinBytes(Coin c) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        c.writeDataStream(dos);
        dos.flush();
        return baos.toByteArray();
    }

    /** Coin.toJSON() -> string -> parse, mimicking the real coins IPC response shape. */
    private static JSONObject asCoinsResponse(Coin c) throws Exception {
        String json = c.toJSON().toString();
        return (JSONObject) new JSONParser().parse(json);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // =============================================================================================
    // 1a. Native Minima coin round-trips byte-identically
    // =============================================================================================
    @Test
    public void fromCoinJson_nativeCoin_isByteIdentical() throws Exception {
        //A realistic on-chain native coin: coinid, our address, amount, mmrentry, block created.
        MiniData coinid  = new MiniData("0xAA11111111111111111111111111111111111111111111111111111111111111");
        MiniData address = new MiniData("0xBB22222222222222222222222222222222222222222222222222222222222222");
        Coin onchain = new Coin(coinid, address, new MiniNumber("123.456"), Token.TOKENID_MINIMA, true);
        onchain.setMMREntryNumber(new MMREntryNumber(4096));
        onchain.setBlockCreated(new MiniNumber("987654"));

        JSONObject coinsJson = asCoinsResponse(onchain);
        System.out.println("=== M2 VERIFY: fromCoinJson (native) ===");
        System.out.println("coins json    : " + coinsJson.toString());

        TxnFactory.InputCoin ic = TxnFactory.fromCoinJson(coinsJson, 0);
        Coin rebuilt = ic.toCoin();

        //Field checks
        assertTrue("coinid",  rebuilt.getCoinID().isEqual(coinid));
        assertTrue("address", rebuilt.getAddress().isEqual(address));
        assertTrue("amount",  rebuilt.getAmount().isEqual(new MiniNumber("123.456")));
        assertTrue("tokenid", rebuilt.getTokenID().isEqual(Token.TOKENID_MINIMA));
        assertTrue("mmrentry", rebuilt.getMMREntryNumber().isEqual(new MMREntryNumber(4096)));
        assertTrue("created", rebuilt.getBlockCreated().isEqual(new MiniNumber("987654")));
        assertEquals("storestate", true, rebuilt.storeState());
        assertNull("no token", rebuilt.getToken());

        //THE round-trip: consensus bytes identical
        assertEquals("native coin bytes identical", hex(coinBytes(onchain)), hex(coinBytes(rebuilt)));
        System.out.println("bytes         : identical (" + coinBytes(onchain).length + " bytes)");
    }

    // =============================================================================================
    // 1b. Native coin with state round-trips byte-identically
    // =============================================================================================
    @Test
    public void fromCoinJson_nativeCoinWithState_isByteIdentical() throws Exception {
        Coin onchain = new Coin(
                new MiniData("0xCC33333333333333333333333333333333333333333333333333333333333333"),
                new MiniData("0xDD44444444444444444444444444444444444444444444444444444444444444"),
                new MiniNumber("10"), Token.TOKENID_MINIMA, true);
        onchain.setMMREntryNumber(new MMREntryNumber(77));
        onchain.setBlockCreated(new MiniNumber("100"));
        ArrayList<StateVariable> st = new ArrayList<>();
        st.add(new StateVariable(0, "99"));                 // NUMBER
        st.add(new StateVariable(1, "0xFEED"));             // HEX
        st.add(new StateVariable(2, "true"));               // BOOL
        onchain.setState(st);

        TxnFactory.InputCoin ic = TxnFactory.fromCoinJson(asCoinsResponse(onchain), 0);
        Coin rebuilt = ic.toCoin();

        assertEquals("state coin bytes identical", hex(coinBytes(onchain)), hex(coinBytes(rebuilt)));
        assertEquals("state size", 3, rebuilt.getState().size());
        System.out.println("=== M2 VERIFY: fromCoinJson (state) === bytes identical, 3 state vars");
    }

    // =============================================================================================
    // 1c. Custom-token coin round-trips byte-identically (incl. the Token descriptor)
    // =============================================================================================
    @Test
    public void fromCoinJson_tokenCoin_isByteIdentical() throws Exception {
        //Build a Token the way the node would (a create-coin id, scale, minima backing, name, script).
        MiniData tokCoinId = new MiniData("0xEE55555555555555555555555555555555555555555555555555555555555555");
        Token token = new Token(tokCoinId, new MiniNumber("2"), new MiniNumber("100"),
                new MiniString("TestCoin"), new MiniString("RETURN TRUE"), new MiniNumber("5000"));
        MiniData tokenid = token.getTokenID();
        assertNotNull("token id computed", tokenid);

        //A coin OF that token: amount is the RAW (scaled-down minima) value; node sets token + tokenid.
        Coin onchain = new Coin(
                new MiniData("0x1111111111111111111111111111111111111111111111111111111111111111"),
                new MiniData("0x2222222222222222222222222222222222222222222222222222222222222222"),
                new MiniNumber("0.5"), Token.TOKENID_MINIMA, true);
        onchain.resetTokenID(tokenid);
        onchain.setToken(token);
        onchain.setMMREntryNumber(new MMREntryNumber(12345));
        onchain.setBlockCreated(new MiniNumber("6000"));

        JSONObject coinsJson = asCoinsResponse(onchain);
        System.out.println("=== M2 VERIFY: fromCoinJson (token) ===");
        System.out.println("coins json    : " + coinsJson.toString());
        System.out.println("tokenamount   : " + coinsJson.get("tokenamount"));

        TxnFactory.InputCoin ic = TxnFactory.fromCoinJson(coinsJson, 0);
        Coin rebuilt = ic.toCoin();

        assertNotNull("token reconstructed", rebuilt.getToken());
        assertTrue("recomputed tokenid matches", rebuilt.getToken().getTokenID().isEqual(tokenid));
        assertTrue("coin tokenid matches", rebuilt.getTokenID().isEqual(tokenid));
        assertTrue("raw amount preserved", rebuilt.getAmount().isEqual(new MiniNumber("0.5")));

        //THE round-trip: full coin bytes (including the embedded Token) identical
        assertEquals("token coin bytes identical", hex(coinBytes(onchain)), hex(coinBytes(rebuilt)));
        System.out.println("bytes         : identical (" + coinBytes(onchain).length + " bytes)");
    }

    // =============================================================================================
    // 2. Aggregation: mixed native + token coins -> per-token totals + counts
    // =============================================================================================
    @Test
    public void aggregate_groupsByTokenWithCorrectTotalsAndCounts() throws Exception {
        //Two native coins (50 + 30) and two token coins (scale 2 -> tokenamount = amount*100).
        MiniData tokCoinId = new MiniData("0xEE55555555555555555555555555555555555555555555555555555555555555");
        Token token = new Token(tokCoinId, new MiniNumber("2"), new MiniNumber("100"),
                new MiniString("TestCoin"), new MiniString("RETURN TRUE"), new MiniNumber("5000"));
        MiniData tokenid = token.getTokenID();

        JSONArray coins = new JSONArray();

        Coin n1 = new Coin(new MiniData("0xA1"), new MiniData("0xDDDD"), new MiniNumber("50"), Token.TOKENID_MINIMA, true);
        n1.setMMREntryNumber(new MMREntryNumber(1)); n1.setBlockCreated(new MiniNumber("10"));
        Coin n2 = new Coin(new MiniData("0xA2"), new MiniData("0xDDDD"), new MiniNumber("30"), Token.TOKENID_MINIMA, true);
        n2.setMMREntryNumber(new MMREntryNumber(2)); n2.setBlockCreated(new MiniNumber("11"));

        Coin t1 = new Coin(new MiniData("0xB1"), new MiniData("0xDDDD"), new MiniNumber("0.5"), Token.TOKENID_MINIMA, true);
        t1.resetTokenID(tokenid); t1.setToken(token); t1.setMMREntryNumber(new MMREntryNumber(3)); t1.setBlockCreated(new MiniNumber("12"));
        Coin t2 = new Coin(new MiniData("0xB2"), new MiniData("0xDDDD"), new MiniNumber("0.25"), Token.TOKENID_MINIMA, true);
        t2.resetTokenID(tokenid); t2.setToken(token); t2.setMMREntryNumber(new MMREntryNumber(4)); t2.setBlockCreated(new MiniNumber("13"));

        //Re-parse each through the node JSON shape.
        for (Coin c : new Coin[]{t1, n1, t2, n2}) {   // deliberately not Minima-first
            coins.add(new JSONParser().parse(c.toJSON().toString()));
        }

        List<CoinAggregator.Agg> aggs = CoinAggregator.aggregate(coins);

        System.out.println("=== M2 VERIFY: aggregate ===");
        for (CoinAggregator.Agg a : aggs) {
            System.out.println("  token=" + a.tokenid.substring(0, Math.min(10, a.tokenid.length()))
                    + "  raw=" + a.rawTotal + "  display=" + a.displayTotal + "  count=" + a.count);
        }

        assertEquals("two tokens", 2, aggs.size());
        //Minima must be first.
        CoinAggregator.Agg minima = aggs.get(0);
        assertTrue("minima first", minima.isMinima());
        assertEquals("minima count", 2, minima.count);
        assertTrue("minima raw = 80", minima.rawTotal.isEqual(new MiniNumber("80")));
        assertTrue("minima display = 80", minima.displayTotal.isEqual(new MiniNumber("80")));

        CoinAggregator.Agg tok = aggs.get(1);
        assertTrue("token tokenid", tok.tokenid.equals(tokenid.to0xString()));
        assertEquals("token count", 2, tok.count);
        //raw = 0.5 + 0.25 = 0.75 ; display (scale 2) = 50 + 25 = 75
        assertTrue("token raw = 0.75", tok.rawTotal.isEqual(new MiniNumber("0.75")));
        assertTrue("token display = 75", tok.displayTotal.isEqual(new MiniNumber("75")));
        assertNotNull("token json kept", tok.tokenJson);
    }
}
