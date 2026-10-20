package com.eurobuddha.wallet;

import java.util.ArrayList;
import org.junit.Test;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.StateVariable;
import org.minima.objects.Coin;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.mmr.MMREntryNumber;
import static org.junit.Assert.*;

/** Synthetic public fixture seed and in-memory counters only. Never submits to a node. */
public class SweepWholeTransferTest {
    @Test public void nativeWholeTransferPreservesStateAmountAndVerifiableSignature() {
        TxnFactoryTest.InMemoryKeyUses uses=new TxnFactoryTest.InMemoryKeyUses();
        WalletCore wallet=new WalletCore("public sweep test fixture seed",uses);TxnFactory factory=new TxnFactory(wallet);
        ArrayList<StateVariable> state=new ArrayList<>();state.add(new StateVariable(1,"[preserve this state]"));state.add(new StateVariable(9,"12345"));
        TxnFactory.InputCoin input=new TxnFactory.InputCoin(new MiniData("0x"+"12".repeat(32)),wallet.getAddress(0).getAddressData(),new MiniNumber("1.25"),
            TxnFactory.TOKEN_MINIMA,new MMREntryNumber(1),new MiniNumber(100),true,state,null,0);
        String destination=wallet.getAddress(1).getAddressData().to0xString();
        assertThrows(IllegalArgumentException.class,()->factory.buildNftTransfer(input,destination,"nft_native"));
        assertEquals(0,uses.currentUses(0));
        TxnFactory.BuiltTxn built=factory.buildWholeTransfer(input,destination,"sweep_native");
        assertEquals(1,built.getTransaction().getAllInputs().size());assertEquals(1,built.getTransaction().getAllOutputs().size());
        Coin output=built.getTransaction().getAllOutputs().get(0);
        assertTrue(output.getAmount().isEqual(new MiniNumber("1.25")));assertTrue(output.getAddress().isEqual(new MiniData(destination)));
        assertTrue(output.getTokenID().isEqual(TxnFactory.TOKEN_MINIMA));assertTrue(output.storeState());
        assertEquals(2,built.getTransaction().getCompleteState().size());
        assertEquals("[preserve this state]",built.getTransaction().getStateValue(1).getData().toString());
        assertEquals("12345",built.getTransaction().getStateValue(9).getData().toString());
        assertEquals(1,uses.currentUses(0));assertEquals(1,built.getNumSignatures());
        TxnRow restored=TxnRow.convertMiniDataVersion(built.getImportData());
        assertTrue(built.getImportData().isEqual(MiniData.getMiniDataVersion(restored)));
        assertTrue(restored.getTransaction().getTransactionID().isEqual(built.getTransactionID()));
        assertTrue(wallet.deriveTreeKey(0).verify(built.getTransactionID(),restored.getWitness().getAllSignatures().get(0)));
        assertTrue(restored.getWitness().getAllCoinProofs().isEmpty());assertTrue(restored.getWitness().getAllScripts().isEmpty());
    }
    @Test public void wrongKeyAddressCannotConsumeASignature() {
        TxnFactoryTest.InMemoryKeyUses uses=new TxnFactoryTest.InMemoryKeyUses();WalletCore wallet=new WalletCore("public sweep test fixture seed",uses);
        TxnFactory.InputCoin input=new TxnFactory.InputCoin(new MiniData("0x"+"34".repeat(32)),wallet.getAddress(1).getAddressData(),MiniNumber.ONE,
            TxnFactory.TOKEN_MINIMA,new MMREntryNumber(1),new MiniNumber(100),true,new ArrayList<>(),null,0);
        assertThrows(IllegalArgumentException.class,()->new TxnFactory(wallet).buildWholeTransfer(input,wallet.getAddress(2).getAddressData().to0xString(),"bad_owner"));
        assertEquals(0,uses.currentUses(0));
    }
}
