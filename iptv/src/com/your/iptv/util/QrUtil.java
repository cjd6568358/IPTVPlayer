package com.your.iptv.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Map;

/** 二维码生成(ZXing core,纯 Java,dalvik 兼容)。 */
public final class QrUtil {

    private QrUtil() {}

    /** @return 白底黑码二维码 Bitmap(带 margin;setPixels 批量写入,避免老机逐像素卡顿) */
    public static Bitmap encode(String content, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        try {
            QRCodeWriter w = new QRCodeWriter();
            Map<EncodeHintType, Object> hints =
                    new java.util.HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            com.google.zxing.common.BitMatrix m =
                    w.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int[] pixels = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                int off = y * sizePx;
                for (int x = 0; x < sizePx; x++) {
                    pixels[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            return bmp;
        } catch (Throwable t) {
            bmp.eraseColor(Color.WHITE);          // 失败兜底:白底(内容不可扫,但不崩溃)
            return bmp;
        }
    }
}
