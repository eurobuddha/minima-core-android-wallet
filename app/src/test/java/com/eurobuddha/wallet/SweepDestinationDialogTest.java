package com.eurobuddha.wallet;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real dialog controls, public fixture and cancellation only; never execute a valid sweep. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class SweepDestinationDialogTest {
    private static final String MX="MxG081PTR905VTUGBKY7KHKV76QVHE99BM691QNC09GP2WPCG1P0QZTHD8SWWVQ";
    private static EditText entry(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=entry(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private static void put(Object object,String key,Object value)throws Exception {
        Field field=SweepFlow.class.getDeclaredField(key);field.setAccessible(true);field.set(object,value);
    }
    @Test public void finalActionRequiresMatchingAddressAndCancelDoesNotStartSweep()throws Exception {

        // Attach an Activity for real views without starting wallet setup or accessing a vault.
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(com.eurobuddha.wallet.R.style.Theme_Utxo);
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        put(flow,"act",activity);
        Method show=SweepFlow.class.getDeclaredMethod("confirmDestination",SweepFlow.Plan.class,String.class);
        show.setAccessible(true);
        show.invoke(flow,new SweepFlow.Plan(),MX);
        AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        EditText input=entry(dialog.getWindow().getDecorView());assertNotNull(input);
        android.widget.Button execute=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertFalse(execute.isEnabled());
        input.setText(MX.substring(0,MX.length()-1)+"0");assertFalse(execute.isEnabled());
        input.setText(MX);assertTrue(execute.isEnabled());
        input.setText("");assertFalse(execute.isEnabled());
        input.setText(MX.toLowerCase(java.util.Locale.ROOT));assertTrue(execute.isEnabled());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());

    }
    @Test public void destinationOutsideSourceWalletCanReachFinalConfirmation()throws Exception {

        MainActivity activity=spy(Robolectric.buildActivity(MainActivity.class).get());
        activity.setTheme(com.eurobuddha.wallet.R.style.Theme_Utxo);
        doReturn(null).when(activity).keyIndexForAddress(anyString());
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        put(flow,"act",activity);
        Method review=SweepFlow.class.getDeclaredMethod("review",SweepFlow.Plan.class,String.class);
        review.setAccessible(true);review.invoke(flow,new SweepFlow.Plan(),MX);
        AlertDialog first=(AlertDialog)ShadowDialog.getLatestDialog();assertTrue(first.isShowing());
        verify(activity).keyIndexForAddress(org.minima.objects.Address.convertMinimaAddress(MX).to0xString());
        assertEquals("Confirm destination…",first.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog confirmation=(AlertDialog)ShadowDialog.getLatestDialog();
        assertNotSame(first,confirmation);assertTrue(confirmation.isShowing());
        assertFalse(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        verify(activity,never()).vault();verify(activity,never()).factory();verify(activity,never()).node();
    }

    private static String visibleText(View view) {
        StringBuilder text=new StringBuilder();
        if(view instanceof android.widget.TextView)text.append(((android.widget.TextView)view).getText()).append("\n");
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)text.append(visibleText(((ViewGroup)view).getChildAt(i)));
        return text.toString();
    }
    @Test public void reviewShowsAssetsAndDestinationWithoutNodeDataOrKeyCounters()throws Exception {

        MainActivity activity=spy(Robolectric.buildActivity(MainActivity.class).get());
        activity.setTheme(com.eurobuddha.wallet.R.style.Theme_Utxo);
        doReturn(null).when(activity).keyIndexForAddress(anyString());
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        put(flow,"act",activity);
        SweepFlow.Plan plan=new SweepFlow.Plan();
        plan.totals.add(new SweepFlow.AssetTotal("0x00","Minima","12.5"));
        plan.wholeItems=3;plan.pendingCount=2;plan.lockedCount=1;
        for(int i=0;i<100;i++)plan.skipped.add("0xFOREIGNCOIN"+i+" — address 0xFOREIGNADDRESS is not in this wallet");
        plan.oversizedWarnings.add("0xHUGEITEM — estimated ~100K");
        Method review=SweepFlow.class.getDeclaredMethod("review",SweepFlow.Plan.class,String.class);
        review.setAccessible(true);review.invoke(flow,plan,MX);
        AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
        String text=visibleText(dialog.getWindow().getDecorView());
        assertTrue(text,text.contains("12.5  Minima"));assertTrue(text,text.contains(MX));
        assertTrue(text,text.contains("whole items: 3"));assertTrue(text,text.contains("Already being sent: 2"));
        assertTrue(text,text.contains("Locked editions staying here: 1"));assertTrue(text,text.contains("Large items: 1"));
        assertTrue(text,text.contains("Nothing is sent yet"));assertTrue(text,text.contains("cannot be undone"));
        assertFalse(text,text.contains("0x"));assertFalse(text,text.contains("262144"));
        verify(activity,never()).keyUses();verify(activity,never()).vault();verify(activity,never()).factory();verify(activity,never()).node();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
    }

}
