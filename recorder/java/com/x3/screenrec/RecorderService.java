package com.x3.screenrec;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Фоновая служба:
 *  - рисует «точку» поверх всех окон (тап — панель, перетаскивание — сдвиг);
 *  - записывает экран (MediaProjection) + звук (микрофон или режим звонка);
 *  - пауза/продолжение/стоп; видео в Movies/X3 (Android 10+) или Movies.
 */
public class RecorderService extends Service {

    public static final String ACTION_STOP = "com.x3.screenrec.STOP";
    public static final String ACTION_START_RECORDING = "com.x3.screenrec.START_RECORDING";
    public static final String ACTION_RECORDING_CANCELED = "com.x3.screenrec.RECORDING_CANCELED";
    public static final String EXTRA_RESULT_CODE = "x3.result_code";
    public static final String EXTRA_RESULT_DATA = "x3.result_data";

    private static final String CHANNEL_ID = "x3_recorder";
    private static final int NOTIFICATION_ID = 1;
    private static final int DOT_SIZE_DP = 54;

    private enum State { IDLE, RECORDING, PAUSED }

    private State state = State.IDLE;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Оверлей
    private WindowManager windowManager;
    private View overlayRoot;
    private WindowManager.LayoutParams overlayParams;
    private TextView dot;
    private GradientDrawable dotBackground;
    private LinearLayout panel;
    private Button btnStart;
    private Button btnCallMode;
    private Button btnPause;
    private Button btnStop;

    // Запись
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;

    // Режим звонка: записывать звук со всего телефона (оба конца звонка),
    // а не только микрофон. Работает там, где система разрешает VOICE_CALL.
    private boolean callMode = false;

    // Тайминги (SystemClock.elapsedRealtime)
    private long recStartedAt = 0L;
    private long pausedTotalMs = 0L;
    private long pauseStartedAt = 0L;

    private String lastFileName = "";

    // Перетаскивание точки
    private float downRawX;
    private float downRawY;
    private int downLpX;
    private int downLpY;
    private boolean dragging;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Запись экрана", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInternal(buildNotification(true));

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START_RECORDING.equals(action)) {
                int code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                onProjectionResult(code, data);
                return;
            }
            if (ACTION_RECORDING_CANCELED.equals(action)) {
                Toast.makeText(this,
                        "Разрешение на запись экрана не выдано", Toast.LENGTH_LONG).show();
                showPanel(true);
                return;
            }
            if (ACTION_STOP.equals(action)) {
                if (state != State.IDLE) {
                    stopRecording();
                } else {
                    stopSelfSafely();
                }
                return;
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Дай приложению разрешение «поверх» в настройках", Toast.LENGTH_LONG).show();
            return;
        }
        ensureOverlay();
    }

    // ---------- запись ----------

    private void onProjectionResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this,
                    "Разрешение на запись экрана не выдано", Toast.LENGTH_LONG).show();
            showPanel(true);
            return;
        }
        if (projection == null) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // Пользователь отозвал доступ (или запись завершилась системой)
                    handler.post(() -> {
                        if (state != State.IDLE) {
                            stopRecording();
                        }
                        releaseProjection();
                        updatePanel();
                    });
                }
            };
            projection.registerCallback(projectionCallback, handler);
        }
        startRecording();
    }

    private void startRecording() {
        if (projection == null || recorder != null) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        if (width > height) {
            int tmp = width;
            width = height;
            height = tmp;
        }
        // Ограничиваем длину стороны (1920), чтобы не душить слабые телефоны
        if (height > 1920) {
            float scale = 1920f / height;
            width = (int) (width * scale);
            height = 1920;
        }
        width &= ~1;
        height &= ~1;

        lastFileName = "X3_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".mp4";

        try {
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setAudioSource(callMode
                    ? MediaRecorder.AudioSource.VOICE_CALL
                    : MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoSize(width, height);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setAudioEncodingBitRate(192_000);
            recorder.setAudioSamplingRate(48_000);

            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = insertMediaStoreRow(lastFileName);
                if (uri == null) {
                    throw new IOException("MediaStore: не удалось создать файл");
                }
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
                recorder.setOutputFile(pfd.getFileDescriptor());
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                    throw new IOException("Нет доступа к папке Movies");
                }
                recorder.setOutputFile(new File(dir, lastFileName));
            }

            recorder.prepare();
            recorder.start();

            Surface surface = recorder.getSurface();
            virtualDisplay = projection.createVirtualDisplay(
                    "X3Recorder", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, handler);

            recStartedAt = SystemClock.elapsedRealtime();
            pausedTotalMs = 0L;
            pauseStartedAt = 0L;
            state = State.RECORDING;

            // После согласия показываем системе тип FGS mediaProjection
            startForegroundInternal(buildNotification(true));
            updatePanel();
            startTicking();
            Toast.makeText(this,
                    callMode ? "Запись началась (режим звонка)" : "Запись началась",
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this,
                    "Не удалось начать запись: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cleanupRecorder();
            state = State.IDLE;
            updatePanel();
        }
    }

    /** Пауза: запись на время, потом «Продолжить» включает её заново (один файл). */
    private void togglePause() {
        if (recorder == null) {
            return;
        }
        if (state == State.RECORDING) {
            try {
                recorder.pause();
                pauseStartedAt = SystemClock.elapsedRealtime();
                state = State.PAUSED;
            } catch (RuntimeException e) {
                Toast.makeText(this,
                        "Пауза на этом устройстве не поддерживается — запись остановлена",
                        Toast.LENGTH_LONG).show();
                stopRecording();
                return;
            }
        } else if (state == State.PAUSED) {
            try {
                recorder.resume();
                pausedTotalMs += SystemClock.elapsedRealtime() - pauseStartedAt;
                pauseStartedAt = 0L;
                state = State.RECORDING;
            } catch (RuntimeException e) {
                Toast.makeText(this,
                        "Не удалось продолжить запись", Toast.LENGTH_LONG).show();
                stopRecording();
                return;
            }
        }
        updatePanel();
    }

    private void stopRecording() {
        stopTicking();
        long durationMs = state == State.IDLE ? 0L : elapsedMs();
        cleanupRecorder();
        state = State.IDLE;
        updatePanel();
        startForegroundInternal(buildNotification(true));
        if (durationMs > 0L) {
            String path = Build.VERSION.SDK_INT >= 29 ? "Movies/X3" : "Movies";
            Toast.makeText(this,
                    "Запись завершена (" + formatTime(durationMs) + ")\nФайл: " + path,
                    Toast.LENGTH_LONG).show();
        }
    }

    private Uri insertMediaStoreRow(String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/X3");
        return getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private void cleanupRecorder() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
        }
    }

    private void releaseProjection() {
        if (projection != null) {
            if (projectionCallback != null) {
                try {
                    projection.unregisterCallback(projectionCallback);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            projection = null;
        }
    }

    private void stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= 26) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // ---------- таймеры ----------

    private long elapsedMs() {
        if (recStartedAt == 0L) {
            return 0L;
        }
        long now = SystemClock.elapsedRealtime();
        long paused = pausedTotalMs;
        if (state == State.PAUSED && pauseStartedAt > 0L) {
            paused += now - pauseStartedAt;
        }
        long result = now - recStartedAt - paused;
        return result > 0L ? result : 0L;
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        String mm = String.format(Locale.US, "%02d", minutes);
        String ss = String.format(Locale.US, "%02d", seconds);
        return hours > 0L ? hours + ":" + mm + ":" + ss : mm + ":" + ss;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == State.RECORDING || state == State.PAUSED) {
                if (dot != null) {
                    dot.setText(formatTime(elapsedMs()));
                }
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, buildNotification(true));
                }
                handler.postDelayed(this, 500L);
            }
        }
    };

    private void startTicking() {
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
    }

    private void stopTicking() {
        handler.removeCallbacks(tickRunnable);
    }

    // ---------- уведомление ----------

    private void startForegroundInternal(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (projection != null) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type);
            } catch (Exception e) {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(boolean withStopAction) {
        String title;
        String text;
        if (state == State.RECORDING) {
            title = "Идёт запись";
            text = formatTime(elapsedMs()) + (callMode ? " · режим звонка" : "");
        } else if (state == State.PAUSED) {
            title = "Запись на паузе";
            text = formatTime(elapsedMs());
        } else {
            title = "X3 Recorder";
            text = "Нажми на точку на экране";
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        if (withStopAction && state != State.IDLE) {
            Intent stopIntent = new Intent(this, RecorderService.class);
            stopIntent.setAction(ACTION_STOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, flags);
            builder.addAction(new Notification.Action.Builder(null, "Стоп", stopPending).build());
        }

        return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(Res.of(this, "ic_stat_rec", "drawable"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    // ---------- оверлей ----------

    private void ensureOverlay() {
        if (overlayRoot != null) {
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        int dotSizePx = (int) (DOT_SIZE_DP * density);

        dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(0xFFE53935);

        dot = new TextView(this);
        dot.setGravity(Gravity.CENTER);
        dot.setTextColor(Color.WHITE);
        dot.setTextSize(10f);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setBackground(dotBackground);
        dot.setText("REC");
        int pad = dotSizePx / 4;
        dot.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dotSizePx, dotSizePx);
        dot.setLayoutParams(dotParams);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int panelPad = (int) (10 * density);
        panel.setPadding(panelPad, panelPad, panelPad, panelPad);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xEE1B1B1F);
        panelBackground.setCornerRadius(16 * density);
        panel.setBackground(panelBackground);
        panel.setVisibility(View.GONE);

        btnStart = makeButton("Записать", 0xFFE53935);
        btnCallMode = makeButton("Режим звонка: выкл", 0xFF455A64);
        btnPause = makeButton("Пауза", 0xFFFB8C00);
        btnStop = makeButton("Стоп", 0xFFD32F2F);

        panel.addView(btnStart);
        panel.addView(btnCallMode);
        panel.addView(btnPause);
        panel.addView(btnStop);

        overlayRoot = new FrameLayout(this);
        overlayRoot.addView(dot, dotParams);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.topMargin = dotSizePx + (int) (8 * density);
        overlayRoot.addView(panel, panelParams);

        int overlayType = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = (int) (12 * density);
        overlayParams.y = (int) (140 * density);

        // Тап по точке — панель; перетаскивание — сдвиг
        dot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downLpX = overlayParams.x;
                        downLpY = overlayParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) + Math.abs(dy) > 10) {
                            dragging = true;
                        }
                        if (dragging) {
                            overlayParams.x = downLpX + dx;
                            overlayParams.y = downLpY + dy;
                            try {
                                windowManager.updateViewLayout(overlayRoot, overlayParams);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) {
                            togglePanel();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        btnStart.setOnClickListener(v -> {
            showPanel(false);
            Intent bridge = new Intent(RecorderService.this, ProjectionBridgeActivity.class);
            bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(bridge);
        });

        btnCallMode.setOnClickListener(v -> {
            if (state != State.IDLE) {
                return; // режим меняется только до начала записи
            }
            callMode = !callMode;
            btnCallMode.setText("Режим звонка: " + (callMode ? "вкл" : "выкл"));
            btnCallMode.setBackgroundColor(callMode ? 0xFF7CB342 : 0xFF455A64);
            Toast.makeText(this, callMode
                            ? "Режим звонка: записывается звук со всего телефона"
                            : "Записывается микрофон",
                    Toast.LENGTH_SHORT).show();
        });

        btnPause.setOnClickListener(v -> togglePause());
        btnStop.setOnClickListener(v -> stopRecording());

        try {
            windowManager.addView(overlayRoot, overlayParams);
        } catch (RuntimeException e) {
            e.printStackTrace();
            overlayRoot = null;
            Toast.makeText(this,
                    "Не удалось показать точку — дай разрешение «поверх»",
                    Toast.LENGTH_LONG).show();
            return;
        }
        updatePanel();
    }

    private Button makeButton(String text, int backgroundColor) {
        float density = getResources().getDisplayMetrics().density;
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(backgroundColor);
        button.setMinWidth(0);
        button.setMinHeight(0);
        int horizontalPad = (int) (18 * density);
        button.setPadding(horizontalPad, 0, horizontalPad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (int) (42 * density));
        params.topMargin = (int) (6 * density);
        button.setLayoutParams(params);
        return button;
    }

    private void togglePanel() {
        if (panel == null) {
            return;
        }
        showPanel(panel.getVisibility() != View.VISIBLE);
    }

    private void showPanel(boolean visible) {
        if (panel == null) {
            return;
        }
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            updatePanel();
        }
    }

    private void updatePanel() {
        if (dot == null || panel == null) {
            return;
        }
        if (state == State.IDLE) {
            dotBackground.setColor(0xFFE53935);
            dot.setText("REC");
            btnStart.setVisibility(View.VISIBLE);
            btnCallMode.setVisibility(View.VISIBLE);
            btnPause.setVisibility(View.GONE);
            btnStop.setVisibility(View.GONE);
        } else {
            dotBackground.setColor(state == State.PAUSED ? 0xFF9E9E9E : 0xFFE53935);
            dot.setText(formatTime(elapsedMs()));
            btnStart.setVisibility(View.GONE);
            btnCallMode.setVisibility(View.GONE);
            btnPause.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            btnPause.setText(state == State.PAUSED ? "Продолжить" : "Пауза");
        }
    }

    @Override
    public void onDestroy() {
        stopTicking();
        cleanupRecorder();
        releaseProjection();
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (RuntimeException ignored) {
            }
            overlayRoot = null;
        }
        super.onDestroy();
    }
}
