package com.eyes.distance;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;

import java.util.concurrent.ExecutionException;

import static java.lang.Math.abs;
import static java.lang.Math.pow;

public class CameraActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    FirebaseVisionFaceDetector detector = FirebaseVision.getInstance()
            .getVisionFaceDetector(new FirebaseVisionFaceDetectorOptions.Builder()
                    .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                    .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                    .build());

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.camera_activity);
        previewView = findViewById(R.id.preview_view);
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
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
                new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ImageProxy::close);
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        preview.setSurfaceProvider(previewView.createSurfaceProvider());
        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector,
                imageAnalysis, preview);

    }

    public void takePicture() {
        previewView = findViewById(R.id.preview_view);
        Bitmap bitmap = previewView.getBitmap();
        analyze(bitmap);
    }

    byte[] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {

        int[] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        byte[] yuv = new byte[inputWidth * inputHeight * 3 / 2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);

        scaled.recycle();

        Log.d("NV21", "SUCCESS");

        return yuv;
    }

    void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff);
                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : (Math.min(Y, 255)));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : (Math.min(V, 255)));
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : (Math.min(U, 255)));
                }
                index++;
            }
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    public void analyze(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        byte[] imgBytes = getNV21(bitmap.getWidth(), bitmap.getHeight(), bitmap);
        FirebaseVisionImageMetadata firebaseVisionImageMetadata = new FirebaseVisionImageMetadata.Builder()
                .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21).
                        setRotation(FirebaseVisionImageMetadata.ROTATION_0).setHeight(bitmap.getHeight()).setWidth(bitmap.getWidth())
                .build();
        FirebaseVisionImage image =
                FirebaseVisionImage.fromByteArray(imgBytes, firebaseVisionImageMetadata);
        detector.detectInImage(image)
                .addOnSuccessListener(
                        faces -> {
                            if (faces.size() <= 0) return;
                            double yAngle = faces.get(0).getHeadEulerAngleY();
                            double zAngle = faces.get(0).getHeadEulerAngleZ();
                            double eyesDistance = abs(faces.get(0).getLandmark(4).getPosition().getX() - faces.get(0).getLandmark(10).getPosition().getX());
                            double yAddition = abs(eyesDistance * pow(Math.tan(yAngle / 180 * 3.14), 2))/3.11;
                            double zAddition = abs(eyesDistance * pow(Math.tan(zAngle / 180 * 3.14), 2))/2.61;
                            double faceDistance = (458 / (eyesDistance + yAddition + zAddition)) * 35 ;
                            ImageView warningImage = findViewById(R.id.warningImage);

                            Log.d("Y_ANGLE", String.valueOf(yAngle));
                            Log.d("Z_ANGLE", String.valueOf(zAngle));
                            Log.d("Y_ADDITION", String.valueOf(yAddition));
                            Log.d("Z_ADDITION", String.valueOf(zAddition));
                            Log.d("EYES_DISTANCE", String.valueOf(eyesDistance));
                            Log.d("FACE_DISTANCE", String.valueOf(faceDistance));
                            if (faceDistance < 35) {
                                warningImage.setVisibility(View.VISIBLE);
                            } else {
                                warningImage.setVisibility(View.INVISIBLE);
                            }

                        }).addOnFailureListener(
                new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.d("ERROR_FACES", String.valueOf(e) + " ERROR");
                    }
                });
    }

}
