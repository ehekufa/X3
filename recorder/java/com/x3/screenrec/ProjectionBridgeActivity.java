package com.x3.screenrec;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Прозрачная activity: служба сама не может получить системный токен
 * на запись экрана, поэтому диалог согласия показываем отсюда,
 * а результат (resultCode + Intent с токеном) передаём в RecorderService.
 */
public class ProjectionBridgeActivity extends Activity {

    private static final int REQ_PROJECTION = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        // Системный диалог «Записывать экран?» показываем от activity
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            Intent serviceIntent = new Intent(this, RecorderService.class);
            serviceIntent.setAction(resultCode == Activity.RESULT_OK && data != null
                    ? RecorderService.ACTION_START_RECORDING
                    : RecorderService.ACTION_RECORDING_CANCELED);
            if (resultCode == Activity.RESULT_OK && data != null) {
                serviceIntent.putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(RecorderService.EXTRA_RESULT_DATA, data);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
        finish();
    }
}
