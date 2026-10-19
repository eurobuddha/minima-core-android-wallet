package com.eurobuddha.wallet;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.fragment.app.FragmentActivity;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;


/** No real keystore, biometric device or stored credential is accessed. */
public class BiometricStoredDataTest {
    @Test public void malformedCiphertextReportsErrorWithoutUnlocking() { check("ct",false); }
    @Test public void malformedIvReportsErrorWithoutUnlocking() { check("iv",false); }
    @Test public void wrongPreferenceTypeReportsErrorWithoutUnlocking() { check("ct",true); }
    private void check(String broken,boolean wrongType) {
        FragmentActivity activity=mock(FragmentActivity.class);
        Context context=mock(Context.class);SharedPreferences prefs=mock(SharedPreferences.class);
        when(activity.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs);
        when(prefs.getString("bio_ct",null)).thenReturn("ct");
        when(prefs.getString("bio_iv",null)).thenReturn("iv");
        if(wrongType)when(prefs.getString("bio_ct",null)).thenThrow(new ClassCastException("fixture"));
        BiometricUnlock.UnlockCallback callback=mock(BiometricUnlock.UnlockCallback.class);
        try(MockedStatic<Base64> base64=mockStatic(Base64.class)) {
            base64.when(()->Base64.decode(anyString(),eq(Base64.NO_WRAP))).thenReturn(new byte[32]);
            base64.when(()->Base64.decode(broken,Base64.NO_WRAP)).thenThrow(new IllegalArgumentException("fixture"));
            BiometricUnlock.unlock(activity,callback);
            verify(callback).onError(nullable(String.class),eq(false));
            verify(callback,never()).onPassphrase(any());verify(callback,never()).onCancelled();
            verify(prefs,never()).edit();
        }
    }
}
