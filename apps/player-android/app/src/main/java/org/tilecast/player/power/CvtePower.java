package org.tilecast.player.power;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serializes fixed CVTE power operations. Their deadline is measured before
 * enqueue, so stale requests cannot unexpectedly toggle the TV later.
 */
public final class CvtePower {
    private static final String TAG = "TilecastCvtePower";
    private static final long MAX_QUEUE_AGE_MS = 9000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tilecast-cvte-power");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Result { void completed(boolean success); }
    private CvtePower() {}

    public static void request(boolean awake, Result callback) {
        final long expiresAt = SystemClock.elapsedRealtime() + MAX_QUEUE_AGE_MS;
        EXECUTOR.execute(() -> {
            boolean success = false;
            if (SystemClock.elapsedRealtime() < expiresAt) {
                try {
                    success = LocalCvteAdb.setAwake(awake);
                } catch (Exception e) {
                    Log.e(TAG, "CVTE request failed", e);
                }
            } else {
                Log.w(TAG, "Expired queued CVTE power request");
            }
            Log.i(TAG, "awake=" + awake + " confirmed=" + success);
            final boolean outcome = success;
            if (callback != null) MAIN.post(() -> callback.completed(outcome));
        });
    }
}
