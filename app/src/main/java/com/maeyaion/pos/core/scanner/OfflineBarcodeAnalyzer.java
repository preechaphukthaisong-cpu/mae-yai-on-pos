package com.maeyaion.pos.core.scanner;

import android.media.Image;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.Executor;

/** ML Kit bundled-model analyzer; barcode decoding does not require network access. */
final class OfflineBarcodeAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    interface Listener {
        void onBarcode(@NonNull String rawValue, int format);
        void onFailure(@NonNull Exception exception);
    }

    private final BarcodeScanner scanner;
    private final Executor callbackExecutor;
    private final Listener listener;
    private final BarcodeEmissionGate emissionGate = new BarcodeEmissionGate(1_000L);
    private volatile boolean closed;

    OfflineBarcodeAnalyzer(@NonNull Executor callbackExecutor, @NonNull Listener listener) {
        this.callbackExecutor = callbackExecutor;
        this.listener = listener;
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        scanner = BarcodeScanning.getClient(options);
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (closed) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        try {
            scanner.process(input)
                    .addOnSuccessListener(callbackExecutor, barcodes -> {
                        long now = SystemClock.elapsedRealtime();
                        for (Barcode barcode : barcodes) {
                            String raw = barcode.getRawValue();
                            if (emissionGate.shouldEmit(raw, now)) {
                                listener.onBarcode(raw, barcode.getFormat());
                            }
                        }
                    })
                    .addOnFailureListener(callbackExecutor, listener::onFailure)
                    .addOnCompleteListener(callbackExecutor, task -> imageProxy.close());
        } catch (RuntimeException processingFailure) {
            imageProxy.close();
            listener.onFailure(processingFailure);
        }
    }

    @Override public void close() {
        closed = true;
        scanner.close();
    }
}
