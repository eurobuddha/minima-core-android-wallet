package com.eurobuddha.wallet;

import org.junit.Test;
import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Public address fixtures only. No device, node, seed, signing or network calls. */
public class SweepDestinationTest {
    // Reused from Future Cash's independently cross-checked MinimaAddressTest golden.
    static final String MX = "MxG081PTR905VTUGBKY7KHKV76QVHE99BM691QNC09GP2WPCG1P0QZTHD8SWWVQ";
    static final String HEX = "0x39EED202FFBE82E953D234F9CDAFC5C94AEC64875760130C8A596403906B1D8B";
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWYZ";

    @Test public void acceptsKnownAddressAndItsCaseVariants() {
        assertEquals(HEX, Address.convertMinimaAddress(MX).to0xString());
        assertTrue(SweepFlow.isValidDestination(MX));
        assertTrue(SweepFlow.isValidDestination(MX.toLowerCase(Locale.ROOT)));
        assertTrue(SweepFlow.isValidDestination(MX.toUpperCase(Locale.ROOT)));
    }
    @Test public void rejectsEverySingleCharacterMutationIncludingCorrectLengthTypos() {
        for (int i=2;i<MX.length();i++) for (char c:ALPHABET.toCharArray()) {
            if (c==MX.charAt(i)) continue;
            String typo=MX.substring(0,i)+c+MX.substring(i+1);
            assertFalse("Accepted mutation at "+i+": "+typo,SweepFlow.isValidDestination(typo));
        }
    }
    @Test public void rejectsRawHexMissingPrefixGarbageWhitespaceAndOversizedInput() {
        for (String invalid:new String[]{null,"",HEX,MX.substring(2),"Mx",MX.substring(0,MX.length()-1),
                "Mx"+"A".repeat(MX.length()-2)," "+MX,MX+" ",MX+"\n",MX+"?",MX+"0".repeat(200),"Mx-1","Mx+1"})
            assertFalse(SweepFlow.isValidDestination(invalid));
    }
    @Test public void checksumForOtherDataLengthsDoesNotMakeAWalletAddress() {
        for(int size:new int[]{0,1,20,31,33,64}) {
            String encoded=Address.makeMinimaAddress(new MiniData(new byte[size]));
            assertEquals(size,Address.convertMinimaAddress(encoded).getLength());
            assertFalse(SweepFlow.isValidDestination(encoded));
        }
    }
    @Test public void rejectsDecoderAliasesAndTrailingPayloadDespiteValidChecksum() {
        for(String alias:new String[]{"Mx0"+MX.substring(2),MX.replace('W','I'),MX.replace('Y','L'),MX.replace('Z','O')}) {
            assertEquals(HEX,Address.convertMinimaAddress(alias).to0xString());
            assertFalse(SweepFlow.isValidDestination(alias));
        }
        byte[] bytes=BaseConverter.decode32(MX);
        String trailing=BaseConverter.encode32(Arrays.copyOf(bytes,bytes.length+1));
        assertEquals(HEX,Address.convertMinimaAddress(trailing).to0xString());
        assertFalse(SweepFlow.isValidDestination(trailing));
    }
    @Test public void secondEntryMustBeTheSameValidAddress() {
        assertTrue(SweepFlow.destinationConfirmed(MX,MX));
        assertTrue(SweepFlow.destinationConfirmed(MX,MX.toLowerCase(Locale.ROOT)));
        String other=Address.makeMinimaAddress(new MiniData(new byte[32]));
        assertTrue(SweepFlow.isValidDestination(other));
        for(String mismatch:new String[]{null,"",HEX,other,MX.substring(1)," "+MX})
            assertFalse(SweepFlow.destinationConfirmed(MX,mismatch));
        assertFalse(SweepFlow.destinationConfirmed(HEX,HEX));
        assertFalse(SweepFlow.destinationConfirmed("Mx"+"A".repeat(60),"Mx"+"A".repeat(60)));
    }
    @Test public void executionRejectsInvalidOrUnconfirmedDestinationBeforeAnyWalletAccess() throws Exception {
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        MainActivity act=mock(MainActivity.class);
        Field owner=SweepFlow.class.getDeclaredField("act");owner.setAccessible(true);owner.set(flow,act);
        Method run=null;
        for(Method m:SweepFlow.class.getDeclaredMethods())if(m.getName().equals("run"))run=m;
        assertNotNull(run);run.setAccessible(true);
        for(String[] pair:new String[][]{{MX,""},{MX,null},{MX,HEX},{HEX,HEX},{"MxBAD","MxBAD"}})
            run.invoke(flow,null,pair[0],pair[1]);
        verifyNoInteractions(act);
    }
}
