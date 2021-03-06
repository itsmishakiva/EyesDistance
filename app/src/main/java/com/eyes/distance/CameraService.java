package com.eyes.distance;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static java.lang.Math.abs;
import static java.lang.Math.pow;

public class CameraService extends LifecycleService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int delay = 1000;
    private boolean stop = false;
    private boolean started = false;
    private Window window;
    private Runnable runnable;
    private ImageCapture imageCapture;
    private FirebaseVisionFaceDetector detector;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        Context context = getApplicationContext();
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                int screenOrientation = display.getRotation();
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetRotation(screenOrientation).build();
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(context));
        runnable = () -> {
            if (!stop) {
                takePicture();
                handler.postDelayed(runnable, delay);
            } else {
                stopSelf(1);
            }
        };
        detector = FirebaseVision.getInstance()
                .getVisionFaceDetector(new FirebaseVisionFaceDetectorOptions.Builder()
                        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                        .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                        .build());
        if (!started) {
            window = new Window(this);
            handler.postDelayed(runnable, delay);
            started = true;
        }
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "my_channel_01";
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Channel human readable title",
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("EyesDistance is running in the foreground")
                    .setContentText("").setOngoing(true).setSmallIcon(R.drawable.hand).build();

            startForeground(1, notification);
        }
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onDestroy() {
        stop = true;
        window.close();
        handler.removeCallbacks(runnable);
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ImageProxy::close);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        cameraProvider.bindToLifecycle(this, cameraSelector,
                imageAnalysis, imageCapture);
    }


    public void takePicture() {
        imageCapture.takePicture(Executors.newSingleThreadExecutor(), new ImageCapture.OnImageCapturedCallback() {
            @RequiresApi(api = Build.VERSION_CODES.Q)
            @SuppressLint("UnsafeExperimentalUsageError")
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                image.getImage();
                Bitmap bitmap = getBitmap(image);
                analyze(bitmap);
                super.onCaptureSuccess(image);
                image.close();
            }
        });
    }

    private Bitmap getBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] clonedBytes = bytes.clone();
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.length);
    }

    byte[] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {
        int[] argb = new int[inputWidth * inputHeight];
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        byte[] yuv = new byte[inputWidth * inputHeight * 3 / 2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);
        scaled.recycle();
        return yuv;
    }

    void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        int R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff);

                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : (Math.min(Y, 255)));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : (Math.min(V, 255)));
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : (Math.min(U, 255)));
                }
                index++;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @SuppressLint("UnsafeExperimentalUsageError")
    public void analyze(Bitmap bitmap) {
        if (bitmap == null) {
            window.close();
            return;
        }
        byte[] imgBytes = getNV21(bitmap.getWidth(), bitmap.getHeight(), bitmap);
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation_test = getResources().getConfiguration().orientation; //test
        int screenOrientation = display.getRotation();
        if (bitmap.getHeight() < bitmap.getWidth()){
            if (screenOrientation == Surface.ROTATION_0) screenOrientation = Surface.ROTATION_270;
            else if (screenOrientation == Surface.ROTATION_90) screenOrientation = Surface.ROTATION_0;
            else if (screenOrientation == Surface.ROTATION_180) screenOrientation = Surface.ROTATION_90;
            else if (screenOrientation == Surface.ROTATION_270) screenOrientation = Surface.ROTATION_180;
        }
        Log.d("ROTATION_TAG_TEST", String.valueOf(orientation_test)); //test
        Log.d("ROTATION_TAG", String.valueOf(screenOrientation)); //test
        Log.d("FACE12HEIGHT", String.valueOf(bitmap.getHeight()));
        Log.d("FACE12WIDTH", String.valueOf(bitmap.getWidth()));
        FirebaseVisionImageMetadata firebaseVisionImageMetadata = new FirebaseVisionImageMetadata.Builder()
                .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                .setRotation(screenOrientation)
                .setHeight(bitmap.getHeight())
                .setWidth(bitmap.getWidth())
                .build();
        FirebaseVisionImage image =
                FirebaseVisionImage.fromByteArray(imgBytes, firebaseVisionImageMetadata);
        detector.detectInImage(image).addOnSuccessListener(
                faces -> {
                    if (faces.size() <= 0) {
                        window.close();
                        Log.d("FACE12", "NOT_FOUND");
                        return;
                    }
                    float positionLeftX;
                    float positionLeftY;
                    float positionRightX;
                    float positionRightY;
                    positionLeftX = faces.get(0).getLandmark(4).getPosition().getX() / bitmap.getWidth() * 2448;
                    positionLeftY = faces.get(0).getLandmark(4).getPosition().getY() / bitmap.getHeight() * 3264;
                    positionRightX = faces.get(0).getLandmark(10).getPosition().getX() / bitmap.getWidth() * 2448;
                    positionRightY = faces.get(0).getLandmark(10).getPosition().getY() / bitmap.getHeight() * 3264;
                    double yAngle = faces.get(0).getHeadEulerAngleY();
                    double eyesDistance = pow(pow(positionLeftX - positionRightX, 2) + pow(positionLeftY - positionRightY, 2), 0.5);
                    double yAddition = abs(eyesDistance * pow(Math.tan(yAngle / 180 * 3.14), 2)) / 3.11;
                    double faceDistance = 400 / (eyesDistance + yAddition) * 35;
                    if (faceDistance < 35 && !stop) {
                        window.open();
                        Log.d("FACE12", "FOUND");
                    } else {
                        window.close();
                        Log.d("FACE12", "NORMAL DISTANCE");
                    }
                }
        ).addOnFailureListener(e -> {
                    window.close();
                    Log.d("ERROR_FINDING_FACES", e + " CRITICAL ERROR");
                }
        );
    }
}
