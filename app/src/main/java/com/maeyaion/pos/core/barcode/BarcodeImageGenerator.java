package com.maeyaion.pos.core.barcode;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;

import java.util.EnumMap;
import java.util.Map;

/** Renders a Code 128 barcode locally; no network or remote image service is used. */
public final class BarcodeImageGenerator {
    private static final int DEFAULT_WIDTH = 1_200;
    private static final int DEFAULT_HEIGHT = 360;
    private static final int BAR_HEIGHT = 250;

    private BarcodeImageGenerator() {}

    @NonNull
    public static Bitmap renderCode128(@NonNull String value) {
        return renderCode128(value, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    @NonNull
    public static Bitmap renderCode128(@NonNull String value, int width, int height) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("Barcode value must contain 1–128 characters");
        }
        if (width < 200 || height < 100 || height <= BAR_HEIGHT) {
            throw new IllegalArgumentException("Image dimensions are too small");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || ch > 126) {
                throw new IllegalArgumentException("Code 128 input must be printable ASCII");
            }
        }

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 16);
        final BitMatrix matrix;
        try {
            matrix = new Code128Writer().encode(value, BarcodeFormat.CODE_128,
                    width, BAR_HEIGHT, hints);
        } catch (WriterException invalid) {
            throw new IllegalArgumentException("Barcode cannot be encoded", invalid);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
        bars.setColor(Color.BLACK);
        for (int x = 0; x < matrix.getWidth(); x++) {
            int top = -1;
            for (int y = 0; y <= matrix.getHeight(); y++) {
                boolean black = y < matrix.getHeight() && matrix.get(x, y);
                if (black && top < 0) top = y;
                if (!black && top >= 0) {
                    canvas.drawRect(x, top, x + 1, y, bars);
                    top = -1;
                }
            }
        }

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.BLACK);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(32f);
        Rect bounds = new Rect();
        label.getTextBounds(value, 0, value.length(), bounds);
        canvas.drawText(value, width / 2f, height - Math.max(18, bounds.bottom + 18), label);
        return bitmap;
    }
}
