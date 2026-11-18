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
                if (analysis.getTargetRotation() == -1) {
                    analysis.setTargetRotation(previewView.getDisplay().getRotation());
                }
                ProcessCameraProvider provider = providerFuture.get();
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
        ProcessCameraProvider provider = cameraProvider;
        if (provider != null) provider.unbind(analysis);
        analyzer.close();
    }
}
