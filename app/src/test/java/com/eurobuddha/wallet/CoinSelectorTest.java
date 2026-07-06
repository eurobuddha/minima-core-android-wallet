package com.eurobuddha.wallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

/** Pure-JVM verification of the greedy coin selector (send/split cover + consolidate smallest). */
public class CoinSelectorTest {

    private static final String MINIMA = "0x00";

    private static JSONObject coin(String id, String amount, String tokenid) {
        JSONObject c = new JSONObject();
        c.put("coinid", id);
        c.put("amount", amount);
        c.put("tokenid", tokenid);
        return c;
    }

    private static JSONArray pool() {
        JSONArray a = new JSONArray();
        a.add(coin("0x01", "5", MINIMA));
        a.add(coin("0x02", "1", MINIMA));
        a.add(coin("0x03", "10", MINIMA));
        a.add(coin("0x04", "3", MINIMA));
        a.add(coin("0xTT", "100", "0xTOKEN"));   // different token, must be ignored
        return a;
    }

    @Test
    public void selectToCover_picksFewestLargestFirst() {
        // Need 12 → largest-first: 10 + 5 = 15 covers with 2 coins.
        List<JSONObject> chosen = CoinSelector.selectToCover(pool(), MINIMA, new MiniNumber("12"));
        assertEquals("two coins", 2, chosen.size());
        assertEquals("largest first (10)", "10", String.valueOf(chosen.get(0).get("amount")));
        assertEquals("then 5", "5", String.valueOf(chosen.get(1).get("amount")));
        assertTrue("covers required", CoinSelector.sumRaw(chosen).isMoreEqual(new MiniNumber("12")));
        System.out.println("=== M3 VERIFY: coin select (send) === need 12 -> [10,5]");
    }

    @Test
    public void selectToCover_insufficientThrows() {
        // Only 19 total of Minima; asking 50 must throw.
        try {
            CoinSelector.selectToCover(pool(), MINIMA, new MiniNumber("50"));
            fail("expected insufficient funds");
        } catch (CoinSelector.InsufficientFundsException expected) {
            System.out.println("=== M3 VERIFY: coin select insufficient === " + expected.getMessage());
        }
    }

    @Test
    public void selectSmallest_forConsolidatePicksAscending() {
        // Smallest 3: 1, 3, 5.
        List<JSONObject> chosen = CoinSelector.selectSmallest(pool(), MINIMA, 3);
        assertEquals("three coins", 3, chosen.size());
        assertEquals("smallest first (1)", "1", String.valueOf(chosen.get(0).get("amount")));
        assertEquals("then 3", "3", String.valueOf(chosen.get(1).get("amount")));
        assertEquals("then 5", "5", String.valueOf(chosen.get(2).get("amount")));
        System.out.println("=== M3 VERIFY: coin select (consolidate) === smallest 3 -> [1,3,5]");
    }

    @Test
    public void selectSmallest_needsThree() {
        JSONArray small = new JSONArray();
        small.add(coin("0x1", "1", MINIMA));
        small.add(coin("0x2", "2", MINIMA));
        try {
            CoinSelector.selectSmallest(small, MINIMA, 20);
            fail("expected < 3 rejection");
        } catch (IllegalArgumentException expected) { /* good */ }
    }
}
