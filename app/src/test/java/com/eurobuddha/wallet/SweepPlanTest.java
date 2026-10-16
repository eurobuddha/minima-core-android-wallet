package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;

import org.junit.Test;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/** Pure-JVM verification of the sweep planner: classification, chunk caps, skip reasons. */
public class SweepPlanTest {

    private static final String MINIMA = "0x00";
    private static final String ADDR_OURS_A = "0xAAAA";
    private static final String ADDR_OURS_B = "0xBBBB";
    private static final String ADDR_FOREIGN = "0xFFFF";

    /** ADDR_OURS_* resolve to key indices; anything else is not ours. */
    private static final SweepFlow.KeyLookup KEYS = addr -> {
        if (addr.startsWith("0xA")) return 0;
        if (addr.startsWith("0xB")) return 1;
        if (addr.startsWith("0xC")) return 2;
        return null;
    };

    private static JSONObject coin(String id, String addr, String amount, String tokenid) {
        JSONObject c = new JSONObject();
        c.put("coinid", id);
        c.put("address", addr);
        c.put("amount", amount);
        c.put("tokenid", tokenid);
        return c;
    }

    private static JSONObject tokenNode(String name, String script) {
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("script", script);
        return t;
    }

    private static JSONObject stateNftCoin(String id, String addr, String stamp) {
        JSONObject c = coin(id, addr, "0.000000000000001", "0xNFT" + id);
        c.put("token", tokenNode("Panda", "LET s=PREVSTATE(0) IF s EQ 0 AND SIGNEDBY(0xCAFE) THEN"
                + " RETURN TRUE ENDIF RETURN SAMESTATE(0 1) AND"
                + " VERIFYOUT(@INPUT GETOUTADDR(@INPUT) @AMOUNT @TOKENID TRUE)"));
        JSONArray state = new JSONArray();
        JSONObject s0 = new JSONObject();
        s0.put("port", "0");
        s0.put("data", stamp);
        state.add(s0);
        c.put("state", state);
        return c;
    }

    @Test
    public void classification_minimaFungibleAndNft() {
        JSONArray coins = new JSONArray();
        coins.add(coin("0x01", ADDR_OURS_A, "5", MINIMA));
        coins.add(coin("0x02", ADDR_OURS_B, "7", MINIMA));
        JSONObject fun = coin("0x03", ADDR_OURS_A, "100", "0xFUN");
        fun.put("token", tokenNode("Fun", "RETURN TRUE"));
        fun.put("tokenamount", "1000");
        coins.add(fun);
        coins.add(stateNftCoin("0x04", ADDR_OURS_B, "3"));   // stamped → sendable, per-coin

        SweepFlow.Plan p = SweepFlow.plan(coins, KEYS, new HashSet<>());

        // 1 whole NFT job + 1 fungible pool + 1 minima pool.
        assertEquals("three jobs", 3, p.jobs.size());
        assertTrue("NFT job first and whole", p.jobs.get(0).whole);
        assertEquals("fungible pool next", "0xFUN", p.jobs.get(1).tokenid);
        assertEquals("minima last", MINIMA, p.jobs.get(2).tokenid);
        assertTrue("nothing skipped", p.skipped.isEmpty());
        // Signatures: 1 (nft) + 1 (fun, one addr) + 2 (minima, two addrs).
        assertEquals("signature count", 4, p.signatures);
        assertEquals("all four coins planned", 4, p.coinIds().size());
    }

    @Test
    public void skips_pendingForeignAndUnstamped() {
        JSONArray coins = new JSONArray();
        coins.add(coin("0x01", ADDR_OURS_A, "5", MINIMA));          // pending
        coins.add(coin("0x02", ADDR_FOREIGN, "7", MINIMA));          // not our key
        coins.add(stateNftCoin("0x03", ADDR_OURS_A, "0"));           // unstamped locked

        HashSet<String> pending = new HashSet<>();
        pending.add("0x01");
        SweepFlow.Plan p = SweepFlow.plan(coins, KEYS, pending);

        assertTrue("nothing sendable", p.isEmpty());
        assertEquals("three skip reasons", 3, p.skipped.size());
    }

    @Test
    public void scriptedToken_goesPerCoinEvenWithoutState() {
        JSONArray coins = new JSONArray();
        JSONObject scripted = coin("0x01", ADDR_OURS_A, "1", "0xSCR");
        scripted.put("token", tokenNode("Scripted", "RETURN SIGNEDBY(0xCAFE)"));
        coins.add(scripted);

        SweepFlow.Plan p = SweepFlow.plan(coins, KEYS, new HashSet<>());
        assertEquals(1, p.jobs.size());
        assertTrue("non-trivial token script transfers whole", p.jobs.get(0).whole);
    }

    @Test
    public void chunking_respectsInputAndKeyCaps() {
        // 25 Minima coins on one address → input cap chunks: 10 + 10 + 5, one sig each.
        JSONArray coins = new JSONArray();
        for (int i = 0; i < 25; i++) coins.add(coin("0xC" + i, ADDR_OURS_A, "1", MINIMA));
        SweepFlow.Plan p = SweepFlow.plan(coins, KEYS, new HashSet<>());
        assertEquals("input-capped chunks", 3, p.jobs.size());
        assertEquals("one signature per chunk", 3, p.signatures);
        for (SweepFlow.Job j : p.jobs) {
            assertTrue(j.coins.size() <= SweepFlow.MAX_INPUTS_PER_TXN);
            assertFalse(j.whole);
        }

        // 6 coins on 6 distinct addresses (all ours via 0xA/0xB/0xC prefixes impossible —
        // use two prefixes × variants): key cap of 4 distinct addresses per chunk.
        JSONArray spread = new JSONArray();
        String[] addrs = {"0xA1", "0xA2", "0xA3", "0xB1", "0xB2", "0xB3"};
        SweepFlow.KeyLookup manyKeys = a -> {
            for (int i = 0; i < addrs.length; i++) if (addrs[i].equals(a)) return i;
            return null;
        };
        for (int i = 0; i < addrs.length; i++) spread.add(coin("0xD" + i, addrs[i], "1", MINIMA));
        SweepFlow.Plan p2 = SweepFlow.plan(spread, manyKeys, new HashSet<>());
        assertEquals("key-capped chunks (4 + 2)", 2, p2.jobs.size());
        assertEquals("4 sigs then 2", 6, p2.signatures);
    }

    @Test
    public void scriptNormalization() {
        assertEquals("RETURN TRUE", SweepFlow.normalizeScript("  return   true "));
        assertFalse(SweepFlow.normalizeScript("RETURN SIGNEDBY(0x1)").equals(SweepFlow.PLAIN_TOKEN_SCRIPT));
    }
}
