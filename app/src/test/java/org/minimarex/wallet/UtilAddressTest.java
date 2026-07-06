package org.minimarex.wallet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Address-form helpers used by the Send flow. Locks in the MINOR "raw 0x recipient warning" predicate
 * and confirms the existing Mx/0x validity check is unchanged.
 */
public class UtilAddressTest {

    private static final String RAW_0X =
            "0x" + repeat("A", 64);          // 0x + 64 hex = a raw, checksum-less address
    private static final String MX =
            "Mx" + repeat("A", 50);          // Mx + 50 alnum = a valid checksum-bearing form (shape)

    @Test
    public void rawAddr_isDetected() {
        assertTrue("0x + 64 hex is a raw address", Util.isRaw0xAddress(RAW_0X));
        assertTrue("raw address is a valid address", Util.isValidAddress(RAW_0X));
    }

    @Test
    public void mxAddr_isNotRaw() {
        assertFalse("Mx addresses are NOT the raw form (they carry a checksum)", Util.isRaw0xAddress(MX));
        assertTrue("Mx address still validates", Util.isValidAddress(MX));
    }

    @Test
    public void malformed_isNeitherRawNorValid() {
        assertFalse(Util.isRaw0xAddress(null));
        assertFalse(Util.isRaw0xAddress("0x1234"));                 // too short
        assertFalse(Util.isRaw0xAddress("0x" + repeat("Z", 64)));   // non-hex
        assertFalse(Util.isValidAddress("garbage"));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
