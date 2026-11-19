package com.maeyaion.pos.core.scanner;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import android.view.Surface;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

/**
 * Lifecycle-bound offline scanner using CameraX and ML Kit's bundled barcode model.
 * The caller must request CAMERA permission before constructing this scanner.
 */
public final class OfflineBarcodeScanner implements AutoCloseable {
    public interface Listener {
        void onBarcode(@NonNull String rawValue, int format);
        void onFailure(@NonNull Exception exception);
    }

    private final ImageAnalysis analysis;
    private final OfflineBarcodeAnalyzer analyzer;
    private volatile ProcessCameraProvider cameraProvider;
    private volatile boolean closed;

    public OfflineBarcodeScanner(@NonNull Context context, @NonNull LifecycleOwner lifecycleOwner,
            @NonNull PreviewView previewView, @NonNull Executor cameraExecutor,
            @NonNull Executor mainExecutor, @NonNull Listener listener) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("CAMERA permission must be granted before scanner creation");
        }
        analyzer = new OfflineBarcodeAnalyzer(cameraExecutor, new OfflineBarcodeAnalyzer.Listener() {
            @Override public void onBarcode(@NonNull String rawValue, int format) {
                listener.onBarcode(rawValue, format);
            }
            @Override public void onFailure(@NonNull Exception exception) {
                listener.onFailure(exception);
            }
        });
        analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, analyzer);

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(context.getApplicationContext());
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                if (closed) return;
                if (previewView.getDisplay() != null) {
                    analysis.setTargetRotation(previewView.getDisplay().getRotation());
                } else {
                    analysis.setTargetRotation(Surface.ROTATION_0);
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);
                cameraProvider = provider;
            } catch (Exception exception) {
                listener.onFailure(exception);
            }
        }, mainExecutor);
    }

    @Override public void close() {
        closed = true;
        ProcessCameraProvider provider = cameraProvider;
        if (provider != null) provider.unbindAll();
        analyzer.close();
    }
}
