package com.x3.screenrec;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Стартовый экран: выдача разрешения «поверх» и запуск точки записи.
 */
public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 100;
    private static final int REQ_POST_NOTIFICATIONS = 101;
    private static final int REQ_WRITE_STORAGE = 102;

    private Button btnOverlay;
    private Button btnStart;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(Res.of(this, "activity_main", "layout"));

        btnOverlay = findViewById(Res.of(this, "btnOverlay", "id"));
        btnStart = findViewById(Res.of(this, "btnStart", "id"));
        statusText = findViewById(Res.of(this, "statusText", "id"));

        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnStart.setOnClickListener(v -> onLaunchClicked());

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void onLaunchClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Сначала дай разрешение «поверх» (кнопка выше)", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
            return;
        }
        maybeRequestNotificationsThenStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQ_AUDIO:
                if (granted(grantResults)) {
                    if (Build.VERSION.SDK_INT < 29
                            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
                        return;
                    }
                    maybeRequestNotificationsThenStart();
                } else {
                    Toast.makeText(this,
                            "Без доступа к микрофону запись не получится", Toast.LENGTH_LONG).show();
                }
                break;
            case REQ_WRITE_STORAGE:
                if (granted(grantResults)) {
                    maybeRequestNotificationsThenStart();
                } else {
                    Toast.makeText(this,
                            "Без права записи в память видео не сохранится (Android 9 и ниже)",
                            Toast.LENGTH_LONG).show();
                }
                break;
            case REQ_POST_NOTIFICATIONS:
                // Уведомления не обязательны — запускаем в любом случае
                startRecorder();
                break;
            default:
                break;
        }
    }

    private void maybeRequestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
            return;
        }
        startRecorder();
    }

    private void startRecorder() {
        Intent intent = new Intent(this, RecorderService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Готово — тапни по красной точке на экране", Toast.LENGTH_SHORT).show();
    }

    private static boolean granted(int[] grantResults) {
        return grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    private void updateStatus() {
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        statusText.setText("Разрешение «поверх»: " + (overlay ? "выдано" : "не выдано")
                + "\nМикрофон: " + (audio ? "выдан" : "не выдан")
                + "\nВидео сохраняется в: "
                + (Build.VERSION.SDK_INT >= 29 ? "Movies/X3" : "Movies"));
    }
}
