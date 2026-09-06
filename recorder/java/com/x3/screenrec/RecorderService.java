package com.x3.screenrec;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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
import java.util.ArrayList;
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
    private FrameLayout overlayRoot;
    private WindowManager.LayoutParams overlayParams;
    private TextView dot;
    private GradientDrawable dotBackground;
    private LinearLayout panel;
    private Button btnStart;
    private Button btnAudioMode;
    private Button btnPause;
    private Button btnStop;

    // Запись
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor currentPfd; // Android 10+: fd на файл MediaStore

    // Источники звука записи: 0 — микрофон, 1 — обычный телефонный звонок,
    // 2 — без звука (только видео, микрофон не трогаем — не мешаем звонкам)
    private static final int AUDIO_MIC = 0;
    private static final int AUDIO_CALL = 1;
    private static final int AUDIO_NONE = 2;

    private int audioMode = AUDIO_MIC;

    // Согласие на запись экрана получено (токен MediaProjection на руках).
    // С этого момента FGS обязан иметь тип mediaProjection,
    // иначе MediaProjection бросает SecurityException.
    private boolean projectionConsented = false;

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
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInternal(buildNotification(true));

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START_RECORDING.equals(action)) {
                int code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                onProjectionResult(code, data);
                return START_STICKY;
            }
            if (ACTION_RECORDING_CANCELED.equals(action)) {
                Toast.makeText(this,
                        "Разрешение на запись экрана не выдано", Toast.LENGTH_LONG).show();
                showPanel(true);
                return START_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                if (state != State.IDLE) {
                    stopRecording();
                } else {
                    stopSelfSafely();
                }
                return START_STICKY;
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Дай приложению разрешение «поверх» в настройках", Toast.LENGTH_LONG).show();
            return START_STICKY;
        }
        ensureOverlay();
        return START_STICKY;
    }

    // ---------- запись ----------

    private void onProjectionResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this,
                    "Разрешение на запись экрана не выдано", Toast.LENGTH_LONG).show();
            showPanel(true);
            return;
        }
        try {
            // Сначала поднимаем тип FGS до mediaProjection,
            // потом трогаем MediaProjection (на Android 10+ иначе SecurityException)
            projectionConsented = true;
            startForegroundInternal(buildNotification(true));

            if (projection == null) {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(resultCode, data);
                projectionCallback = new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        // Пользователь отозвал доступ (или запись завершилась системой)
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (state != State.IDLE) {
                                    stopRecording();
                                }
                                releaseProjection();
                                updatePanel();
                            }
                        });
                    }
                };
                projection.registerCallback(projectionCallback, handler);
            }
            startRecording();
        } catch (Exception e) {
            android.util.Log.e("X3Recorder", "onProjectionResult failed", e);
            releaseProjection();
            state = State.IDLE;
            updatePanel();
            showPanel(true);
            Toast.makeText(this,
                    "Не удалось начать запись:\n" + e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Набор параметров MediaRecorder для одной попытки prepare().
     *  audioRateHz = 0 — не задавать (дефолт), audioChannels = 0 — не задавать. */
    private static final class RecorderConfig {
        final boolean audioOn;
        final int audioSource;
        final int width;
        final int height;
        final int videoBitRate;
        final int audioBitRate;
        final int audioRateHz;
        final int audioChannels;

        RecorderConfig(boolean audioOn, int audioSource, int width, int height,
                       int videoBitRate, int audioBitRate, int audioRateHz,
                       int audioChannels) {
            this.audioOn = audioOn;
            this.audioSource = audioSource;
            this.width = width;
            this.height = height;
            this.videoBitRate = videoBitRate;
            this.audioBitRate = audioBitRate;
            this.audioRateHz = audioRateHz;
            this.audioChannels = audioChannels;
        }
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
        // Кодировщики H.264 требуют размер кратно 16
        width = Math.max(16, (width / 16) * 16);
        height = Math.max(16, (height / 16) * 16);

        // Запасной вариант: длинная сторона 1280 (720p)
        float fScale = 1280f / Math.max(width, height);
        int fallbackWidth = Math.max(16, ((int) (width * fScale) / 16) * 16);
        int fallbackHeight = Math.max(16, ((int) (height * fScale) / 16) * 16);

        lastFileName = "X3_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".mp4";

        // Если режим со звуком, но разрешения на микрофон нет —
        // честно говорим, а не кидаем тайное IllegalStateException
        if (audioMode != AUDIO_NONE
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            state = State.IDLE;
            updatePanel();
            showPanel(true);
            Toast.makeText(this,
                    "Нет разрешения на микрофон:\nНастройки → Приложения → X3 Recorder → "
                            + "Разрешения → Микрофон → Разрешить",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Цепочка попыток: сначала то, что выбрал пользователь,
        // потом варианты звука (некоторые устройства прихотливые),
        // и напоследок — хотя бы видео без звука
        ArrayList<RecorderConfig> attempts = new ArrayList<RecorderConfig>();
        if (audioMode == AUDIO_CALL) {
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.VOICE_CALL,
                    width, height, 8_000_000, 192_000, 48_000, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.MIC,
                    width, height, 8_000_000, 192_000, 48_000, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.CAMCORDER,
                    width, height, 8_000_000, 192_000, 48_000, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.MIC,
                    fallbackWidth, fallbackHeight, 4_000_000, 128_000, 0, 0));
        } else if (audioMode == AUDIO_NONE) {
            // Без звука: микрофон вообще не открываем, чтобы не мешать звонкам
            attempts.add(new RecorderConfig(false, MediaRecorder.AudioSource.MIC,
                    width, height, 8_000_000, 192_000, 0, 0));
            attempts.add(new RecorderConfig(false, MediaRecorder.AudioSource.MIC,
                    fallbackWidth, fallbackHeight, 4_000_000, 128_000, 0, 0));
        } else {
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.MIC,
                    width, height, 8_000_000, 192_000, 48_000, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.CAMCORDER,
                    width, height, 8_000_000, 192_000, 48_000, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.MIC,
                    fallbackWidth, fallbackHeight, 4_000_000, 128_000, 0, 0));
            attempts.add(new RecorderConfig(true, MediaRecorder.AudioSource.MIC,
                    fallbackWidth, fallbackHeight, 4_000_000, 128_000, 44_100, 2));
        }
        if (audioMode != AUDIO_NONE) {
            // Последний рубеж: если устройство не принимает ни один вариант
            // со звуком — хотя бы видео без звука (лучше, чем ничего)
            attempts.add(new RecorderConfig(false, MediaRecorder.AudioSource.MIC,
                    width, height, 8_000_000, 192_000, 0, 0));
            attempts.add(new RecorderConfig(false, MediaRecorder.AudioSource.MIC,
                    fallbackWidth, fallbackHeight, 4_000_000, 128_000, 0, 0));
        }

        MediaRecorder prepared = null;
        RecorderConfig used = null;
        Exception lastError = null;
        for (int i = 0; i < attempts.size() && prepared == null; i++) {
            try {
                prepared = createPreparedRecorder(attempts.get(i));
                used = attempts.get(i);
            } catch (Exception e) {
                lastError = e;
                closeCurrentPfd();
                android.util.Log.w("X3Recorder", "prepare attempt " + i + " failed", e);
            }
        }

        if (prepared == null || used == null) {
            state = State.IDLE;
            updatePanel();
            showPanel(true);
            RecorderConfig last = attempts.get(attempts.size() - 1);
            Toast.makeText(this,
                    "Не удалось начать запись: "
                            + (lastError != null
                            ? lastError.getClass().getSimpleName() + ": "
                                    + String.valueOf(lastError.getMessage())
                            : "неизвестная причина")
                            + "\nПоследняя попытка: "
                            + (last.audioOn ? "видео + звук" : "только видео")
                            + " " + last.width + "x" + last.height,
                    Toast.LENGTH_LONG).show();
            return;
        }
        recorder = prepared;

        try {
            recorder.start();

            Surface surface = recorder.getSurface();
            virtualDisplay = projection.createVirtualDisplay(
                    "X3Recorder", used.width, used.height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, handler);

            recStartedAt = SystemClock.elapsedRealtime();
            pausedTotalMs = 0L;
            pauseStartedAt = 0L;
            state = State.RECORDING;

            startForegroundInternal(buildNotification(true));
            updatePanel();
            startTicking();

            int usedIndex = attempts.indexOf(used);
            String message;
            if (!used.audioOn && audioMode != AUDIO_NONE) {
                message = "Звук не завёлся — записываю только видео";
            } else if (audioMode == AUDIO_CALL && usedIndex == 1) {
                message = "Устройство не даёт звук звонка — записываю с микрофона";
            } else if (usedIndex != 0) {
                message = "Запись началась в " + used.width + "x" + used.height
                        + " (резервные параметры)";
            } else if (audioMode == AUDIO_CALL) {
                message = "Запись началась (режим звонка)";
            } else if (audioMode == AUDIO_NONE) {
                message = "Запись без микрофона — звонку мешать не будет";
            } else {
                message = "Запись началась";
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // prepare прошёл, но start/virtualDisplay упали — тоже не роняем приложение
            android.util.Log.e("X3Recorder", "startRecording failed", e);
            cleanupRecorder();
            state = State.IDLE;
            updatePanel();
            showPanel(true);
            Toast.makeText(this,
                    "Не удалось начать запись:\n" + e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Создаёт MediaRecorder с нужными параметрами и выполняет prepare().
     *  При неудаче сам себя освобождает, чтобы не занимать аудио-сеанс. */
    private MediaRecorder createPreparedRecorder(RecorderConfig cfg) throws IOException {
        MediaRecorder r = new MediaRecorder();
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (cfg.audioOn) {
                r.setAudioSource(cfg.audioSource);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(cfg.audioBitRate);
                if (cfg.audioRateHz > 0) {
                    r.setAudioSamplingRate(cfg.audioRateHz);
                }
                if (cfg.audioChannels > 1) {
                    r.setAudioChannels(cfg.audioChannels);
                }
            }
            r.setVideoSize(cfg.width, cfg.height);
            r.setVideoEncodingBitRate(cfg.videoBitRate);
            r.setVideoFrameRate(30);

            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = insertMediaStoreRow(lastFileName);
                if (uri == null) {
                    throw new IOException("MediaStore: не удалось создать файл");
                }
                currentPfd = getContentResolver().openFileDescriptor(uri, "w");
                r.setOutputFile(currentPfd.getFileDescriptor());
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                    throw new IOException("Нет доступа к папке Movies");
                }
                r.setOutputFile(new File(dir, lastFileName));
            }

            r.prepare();
            return r;
        } catch (Exception e) {
            try {
                r.release();
            } catch (Exception ignored) {
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()), e);
        }
    }

    private void closeCurrentPfd() {
        if (currentPfd != null) {
            try {
                currentPfd.close();
            } catch (Exception ignored) {
            }
            currentPfd = null;
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
            if (projectionConsented || projection != null) {
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
            String suffix = audioMode == AUDIO_CALL ? " · звонок"
                    : audioMode == AUDIO_NONE ? " · без микрофона" : "";
            text = formatTime(elapsedMs()) + suffix;
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

        int icon;
        try {
            icon = Res.of(this, "ic_stat_rec", "drawable");
        } catch (Exception e) {
            icon = android.R.drawable.ic_menu_camera; // запасной системный
        }

        return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(icon)
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
        btnAudioMode = makeButton("Звук: микрофон", 0xFF455A64);
        btnPause = makeButton("Пауза", 0xFFFB8C00);
        btnStop = makeButton("Стоп", 0xFFD32F2F);

        panel.addView(btnStart);
        panel.addView(btnAudioMode);
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

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(false);
                try {
                    Intent bridge = new Intent(RecorderService.this, ProjectionBridgeActivity.class);
                    bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(bridge);
                } catch (Exception e) {
                    android.util.Log.e("X3Recorder", "start bridge failed", e);
                    showPanel(true);
                    Toast.makeText(RecorderService.this,
                            "Не удалось открыть запрос записи:\n"
                                    + e.getClass().getSimpleName() + ": "
                                    + String.valueOf(e.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        btnAudioMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state != State.IDLE) {
                    return; // звук меняется только до начала записи
                }
                audioMode = (audioMode + 1) % 3;
                btnAudioMode.setText(soundModeLabel());
                btnAudioMode.setBackgroundColor(modeColor());
                Toast.makeText(RecorderService.this, soundModeHint(),
                        Toast.LENGTH_LONG).show();
            }
        });

        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePause();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopRecording();
            }
        });

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

    private String soundModeLabel() {
        switch (audioMode) {
            case AUDIO_CALL:
                return "Звук: звонок";
            case AUDIO_NONE:
                return "Звук: нет";
            default:
                return "Звук: микрофон";
        }
    }

    private int modeColor() {
        switch (audioMode) {
            case AUDIO_CALL:
                return 0xFF7CB342;
            case AUDIO_NONE:
                return 0xFF7E57C2;
            default:
                return 0xFF455A64;
        }
    }

    private String soundModeHint() {
        switch (audioMode) {
            case AUDIO_CALL:
                return "Звук обычного телефонного звонка. "
                        + "К звонкам в мессенджерах не относится";
            case AUDIO_NONE:
                return "Видео без звука. Микрофон не трогаем — "
                        + "не будет мешать звонкам в мессенджерах";
            default:
                return "Микрофон. Для звонка в мессенджере держи планшет "
                        + "на динамике — запишутся обе стороны";
        }
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
            btnAudioMode.setVisibility(View.VISIBLE);
            btnAudioMode.setText(soundModeLabel());
            btnAudioMode.setBackgroundColor(modeColor());
            btnPause.setVisibility(View.GONE);
            btnStop.setVisibility(View.GONE);
        } else {
            dotBackground.setColor(state == State.PAUSED ? 0xFF9E9E9E : 0xFFE53935);
            dot.setText(formatTime(elapsedMs()));
            btnStart.setVisibility(View.GONE);
            btnAudioMode.setVisibility(View.GONE);
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
