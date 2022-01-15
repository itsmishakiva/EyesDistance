package com.eyes.distance;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {
    String buttonText = "STOP";

    @SuppressLint("BatteryLife")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.camera_activity);
        Button restartButton = findViewById(R.id.restartButton);
        Context context = getApplicationContext();
        Intent intent = new Intent(context, CameraService.class);
        restartButton.setOnClickListener(v -> {
            if (buttonText.equals("STOP")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.stopService(intent);
                }
                buttonText = "START";
            } else {
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
        Intent intent = new Intent(context, CameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.stopService(intent);
            context.startForegroundService(intent);
        }
        super.onStart();
    }
}
