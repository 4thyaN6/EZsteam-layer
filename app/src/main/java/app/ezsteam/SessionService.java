package app.ezsteam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service held while a Steam session runs: keeps the app (and PRoot with everything
 * under it) at foreground priority when the user leaves the screen, so a download or a game
 * does not slow down or freeze in the background. Also holds a CPU wake lock and a Wi-Fi lock
 * for the session. Shows one ongoing notification; tapping it brings the app back.
 * Steam 세션이 도는 동안 유지하는 포그라운드 서비스: 사용자가 화면을 떠나도 앱(과 PRoot 및 그 아래 전부)을
 * 포그라운드 우선순위로 유지해 다운로드나 게임이 백그라운드에서 느려지거나 멈추지 않게 함. 세션 동안 CPU
 * 깨우기 잠금과 Wi-Fi 잠금도 잡음. 진행 중 알림 하나를 보여 주며 누르면 앱으로 돌아감
 *
 * Why: a download ran at 134-147 Mbps for 3 minutes, then at 3 Mbps from both CDN servers at once
 * (content_log.txt, 2026-10-18), a local slowdown; the app had no foreground service.
 * 이유: 다운로드가 3분 동안 134~147 Mbps였다가 두 CDN 서버에서 동시에 3 Mbps로 떨어졌음(content_log.txt,
 * 2026-10-18). 기기 쪽 원인이며, 앱에는 포그라운드 서비스가 없었음
 *
 * targetSdk 28: no foregroundServiceType needed (required from 34); FOREGROUND_SERVICE and
 * WAKE_LOCK are normal permissions (granted at install, nothing asked).
 * targetSdk 28: foregroundServiceType 불필요(34부터 필요). FOREGROUND_SERVICE와 WAKE_LOCK은 일반 권한
 * (설치 시 허용, 묻지 않음)
 */
public final class SessionService extends Service {

    private static final String CHANNEL = "ezs-session";
    private static final int NOTE_ID = 1;

    private PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wifi;

    /** Start for the running session (repeated calls are harmless) / 세션용 시작(여러 번 불러도 됨) */
    static void start(Context c) {
        Intent i = new Intent(c, SessionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            // Never block the session over this / 이것 때문에 세션을 막지 않음
            android.util.Log.w("ezsteam", "session service: " + e);
        }
    }

    /** Stop when the session ends / 세션이 끝나면 멈춤 */
    static void stop(Context c) {
        c.stopService(new Intent(c, SessionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Ongoing notification required by a foreground service / 포그라운드 서비스에 필요한 진행 중 알림
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.note_channel), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.note_title))
                .setContentText(getString(R.string.note_text))
                .setOngoing(true);
        // Tap: back to the running app (launcher intent brings the existing task forward)
        // 누르면 실행 중인 앱으로 돌아감(실행기 인텐트가 기존 작업을 앞으로 가져옴)
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        }
        startForeground(NOTE_ID, b.build());

        // CPU stays awake with the screen off; Wi-Fi stays out of power saving
        // 화면이 꺼져도 CPU가 깨어 있게 함. Wi-Fi가 절전으로 들어가지 않게 함
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ezsteam:session");
        wake.setReferenceCounted(false);
        wake.acquire();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ezsteam:session");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Not restarted by Android after a kill: the session is gone then
        // 종료된 뒤 안드로이드가 다시 시작하지 않음: 그때는 세션도 없음
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
