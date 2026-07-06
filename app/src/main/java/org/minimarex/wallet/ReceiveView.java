package org.minimarex.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Receive tab: shows OUR primary wallet address (derived locally from the seed by
 * {@link WalletCore}) with a QR code. Unlike the utxoWallet's Receive (which asks the node for one of
 * its own addresses), this is a self-custodial address the node does not own — so there is no node
 * round-trip here; the address is a pure local derivation. Funding it is how the wallet gets coins for
 * later on-device Send testing.
 */
public class ReceiveView extends BaseView {

    private final TextView address;
    private final ImageView qr;

    public ReceiveView(MainActivity a) {
        super(a, R.layout.view_receive);
        address = find(R.id.rcvAddress);
        qr = find(R.id.rcvQr);

        Button copy = find(R.id.rcvCopy);
        copy.setOnClickListener(v -> copyAddress());
        Button refreshBtn = find(R.id.rcvRefresh);
        refreshBtn.setText("Copy");
        refreshBtn.setOnClickListener(v -> copyAddress());
        refreshBtn.setTextColor(Design.accent());

        root.setBackgroundColor(Design.bg());
        address.setBackgroundColor(Design.surface());
        address.setTextColor(Design.text());
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        copy.setTextColor(Design.onAccent());
        refresh();
    }

    /** Repaints from OUR locally-derived Mx address. */
    @Override
    public void refresh() {
        String addr = act.defaultAddress();
        if (addr == null || addr.isEmpty()) {
            address.setText("Deriving address…");
            qr.setImageBitmap(null);
            return;
        }
        address.setText(addr);
        renderQr(addr);
    }

    @Override
    public void onShown() {
        refresh();
    }

    /** Encodes the address to a QR bitmap off the UI thread; tag guards against stale results. */
    private void renderQr(final String text) {
        qr.setTag(text);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int size = 480;
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
            } catch (Exception e) {
                bmp = null;
            }
            final Bitmap result = bmp;
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (text.equals(qr.getTag())) qr.setImageBitmap(result);
            });
        }).start();
    }

    private void copyAddress() {
        String addr = act.defaultAddress();
        if (addr == null || addr.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Minima address", addr));
        Toast.makeText(act, "Address copied", Toast.LENGTH_SHORT).show();
    }
}
