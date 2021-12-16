package com.eyes.distance;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

import static android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;

public class CameraActivity extends AppCompatActivity {
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    String buttonText = "STOP";

    @SuppressLint("BatteryLife")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.camera_activity);
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
        Button restartButton = findViewById(R.id.restartButton);
        Context context = getApplicationContext();
        Intent intent = new Intent(this, CameraService.class);
        restartButton.setOnClickListener(v -> {
            if (buttonText.equals("STOP")){
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.stopService(intent);
                }
                buttonText = "START";
            }
            else{
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                }
                buttonText = "STOP";
            }
            restartButton.setText(buttonText);
        });
        super.onCreate(savedInstanceState);
    }


    @Override
    protected void onStart() {
        Context context = getApplicationContext();
        Intent intent = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.stopService(intent);
            context.startService(intent);
        }
        super.onStart();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder().setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ImageProxy::close);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        cameraProvider.bindToLifecycle(this, cameraSelector,
                imageAnalysis);
    }
}
