package com.eyes.distance;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.impl.ImageCaptureConfig;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleService;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;

import java.nio.ByteBuffer;
import java.security.Provider;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static java.lang.Math.abs;
import static java.lang.Math.pow;

public class CameraService extends LifecycleService {
    Handler handler = new Handler(Looper.getMainLooper());
    boolean stop = false;
    Runnable runnable = new Runnable() {
        public void run() {
            //Log.d("SERVICE", "REPEAT");
            if (!stop){
                takePicture();
                handler.postDelayed(runnable, delay);}
            else{
                stopSelf(1);
            }
        }
    };
    int delay = 1000;
    boolean started = false;
    boolean dialogOpened = false;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    ImageCapture imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
    FirebaseVisionFaceDetector detector = FirebaseVision.getInstance()
            .getVisionFaceDetector(new FirebaseVisionFaceDetectorOptions.Builder()
                    .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                    .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                    .build());
    public static Window window;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        if (!started){
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
        window = new Window(this);
        handler.postDelayed(runnable, delay);
        started = true;}
        Log.d("SERVICE", "CREATE");
       super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Log.d("SERVICE", "COMMAND");
        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "my_channel_01";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Channel human readable title",
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("").build();

            startForeground(1, notification);
        }
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onDestroy() {
        stop = true;
        window.close();
        Log.d("SERVICE", "CLOSE_WINDOW");
        handler.removeCallbacks(runnable);
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
        Log.d("SERVICE", "DESTROY");
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ImageProxy::close);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector,
                imageAnalysis, imageCapture);
    }


    public void takePicture() {
        imageCapture.takePicture(Executors.newSingleThreadExecutor(), new ImageCapture.OnImageCapturedCallback()
        {
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

            @Override
            public void onError(@NonNull ImageCaptureException exception) {

            }
        });
    }
    private Bitmap getBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        byte[] clonedBytes = bytes.clone();
        //Log.d("SERVICE", "DONE");
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.length);
    }

    byte[] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {

        int[] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        byte[] yuv = new byte[inputWidth * inputHeight * 3 / 2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);

        scaled.recycle();

        //Log.d("NV21", "SUCCESS");

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

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @SuppressLint("UnsafeExperimentalUsageError")
    public void analyze(Bitmap bitmap) {
        if (bitmap == null) {
            window.close();
            return;
        }
        byte[] imgBytes = getNV21(bitmap.getWidth(), bitmap.getHeight(), bitmap);
        Display display = ((WindowManager)
                getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int screenOrientation = display.getRotation();
        FirebaseVisionImageMetadata firebaseVisionImageMetadata = new FirebaseVisionImageMetadata.Builder()
                .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21).
                        setRotation(screenOrientation).setHeight(bitmap.getHeight()).setWidth(bitmap.getWidth())
                .build();
        FirebaseVisionImage image =
                FirebaseVisionImage.fromByteArray(imgBytes, firebaseVisionImageMetadata);
        detector.detectInImage(image)
                .addOnSuccessListener(
                        faces -> {
                            if (faces.size() <= 0){
                                //window.close();
                                window.close();
                                return;
                            }
                            double yAngle = faces.get(0).getHeadEulerAngleY();
                            double zAngle = faces.get(0).getHeadEulerAngleZ();
                            double eyesDistance = pow(pow(faces.get(0).getLandmark(4).getPosition().getX() - faces.get(0).getLandmark(10).getPosition().getX(), 2) + pow(faces.get(0).getLandmark(4).getPosition().getY() - faces.get(0).getLandmark(10).getPosition().getY(), 2), 0.5);
                            double yAddition = abs(eyesDistance * pow(Math.tan(yAngle / 180 * 3.14), 2))/3.11;
                            //double zAddition = abs(eyesDistance * pow(Math.tan(zAngle / 180 * 3.14), 2))/2.61;
                            double faceDistance = 400 / (eyesDistance + yAddition) * 35;
                            if (faceDistance < 35 && !stop) {
                                //Log.d("DISTANCE", "TOO LITTLE");
                                    window.open("TOO CLOSE TOO SCREEN");

                            } else {
                                //Log.d("DISTANCE", "NORMAL");
                                window.close();
                                    //window.close();

                            }

                        }).addOnFailureListener(
                new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        window.close();
                        Log.d("ERROR_FACES", String.valueOf(e) + " ERROR");
                    }
                });
    }
}
