package com.libfriend.teamtasks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 비서 대기: 마이크로 소리를 듣다가 "박수 두 번"을 알아채면 기본 어시스턴트(Claude)를 띄움.
 * 상단 알림이 떠 있는 동안만 동작 (안드로이드 마이크 포그라운드 서비스).
 */
public class ClapService extends Service {
    public static final String ACTION_STOP = "com.libfriend.teamtasks.CLAP_STOP";
    static final String PREFS = "lf_clap";
    static final String CH = "lf_listen";
    static final int NOTI_ID = 1001;
    static final int RATE = 16000;
    static final int FRAME = 256; // 16ms

    static volatile boolean running = false;
    static volatile int clapCount = 0;      // 테스트용: 인식한 박수 수
    static volatile int triggerCount = 0;   // Claude 부른 횟수
    static volatile long lastTriggerAt = 0;
    static volatile String lastError = "";

    private volatile boolean stop = false;
    private Thread worker;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ClapService.class);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, ClapService.class);
        i.setAction(ACTION_STOP);
        try { ctx.startService(i); } catch (Exception e) { ctx.stopService(new Intent(ctx, ClapService.class)); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTI_ID, n);
        } catch (Exception e) {
            lastError = "시작 실패: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stop = false;
            worker = new Thread(this::listen, "lf-clap");
            worker.start();
        }
        running = true;
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "비서 대기", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("박수 두 번으로 Claude 부르기");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 1, open, piFlags);
        Intent off = new Intent(this, ClapService.class);
        off.setAction(ACTION_STOP);
        PendingIntent piOff = PendingIntent.getService(this, 2, off, piFlags);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("비서 대기 중")
                .setContentText("박수 두 번 → Claude")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(piOpen)
                .addAction(0, "끄기", piOff)
                .build();
    }

    /** 감도 1(둔감)~5(민감) */
    private int sens() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("sens", 3);
    }

    private void listen() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            rec.startRecording();
        } catch (Exception e) {
            lastError = "마이크 열기 실패: " + e.getMessage();
            new Handler(Looper.getMainLooper()).post(this::stopSelf);
            return;
        }
        lastError = "";
        short[] buf = new short[FRAME];
        double bg = 2e5;                // 배경 소음 에너지 (이동 평균)
        long frameNo = 0;
        long pendingAt = -1;            // 박수 후보 시작 프레임
        double pendingE = 0;
        long lastClap = -1000;          // 마지막 박수 프레임
        long cooldownUntil = 0;         // 이 프레임까지 무시

        while (!stop) {
            int n = rec.read(buf, 0, FRAME);
            if (n <= 0) continue;
            frameNo++;
            double e = 0, d = 0; int peak = 0, prev = buf[0];
            for (int i = 0; i < n; i++) {
                int x = buf[i];
                e += (double) x * x;
                int dx = x - prev; d += (double) dx * dx; prev = x;
                int ax = Math.abs(x); if (ax > peak) peak = ax;
            }
            e /= n; d /= n;
            if (frameNo < cooldownUntil) { bg = bg * 0.98 + e * 0.02; continue; }

            int s = sens();
            int peakMin = 16000 - s * 2200;          // 감도 3 → 9400
            double rise = 14 - s * 2;                 // 감도 3 → 배경의 8배
            double hf = e > 0 ? d / e : 0;            // 고음 비율 (박수 ≈ 0.4 이상, 말소리 ≈ 0.1 이하)

            if (pendingAt < 0) {
                if (peak > peakMin && e > bg * rise && hf > 0.25) { pendingAt = frameNo; pendingE = e; }
                else bg = bg * 0.97 + e * 0.03;
            } else if (frameNo - pendingAt >= 6) {  // ~100ms 뒤: 소리가 빨리 꺼졌으면 박수
                boolean clap = e < pendingE * 0.3;
                pendingAt = -1;
                if (clap) {
                    clapCount++;
                    long gap = frameNo - 6 - lastClap;   // 프레임 단위 (1프레임 = 16ms)
                    if (gap >= 10 && gap <= 50) {        // 0.16초 ~ 0.8초 간격 = 박수 두 번
                        lastClap = -1000;
                        cooldownUntil = frameNo + 45000 / 16;  // 45초 동안 무시 (Claude와 대화하는 동안)
                        triggerCount++;
                        lastTriggerAt = System.currentTimeMillis();
                        new Handler(Looper.getMainLooper()).post(this::wakeClaude);
                    } else {
                        lastClap = frameNo - 6;
                    }
                }
            }
        }
        try { rec.stop(); } catch (Exception ignored) {}
        rec.release();
    }

    /** 화면 켜고 기본 어시스턴트(Claude) 띄우기. 실패하면 Claude 앱 열기 */
    void wakeClaude() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150);
            new Handler(Looper.getMainLooper()).postDelayed(tg::release, 400);
        } catch (Exception ignored) {}
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "lf:clap");
                wl.acquire(5000);
            }
        } catch (Exception ignored) {}
        launchAssistant(this);
    }

    static boolean launchAssistant(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = p.getString("mode", "assist");
        PackageManager pm = ctx.getPackageManager();
        if (!"app".equals(mode)) {
            try {
                Intent a = new Intent(Intent.ACTION_ASSIST);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                List<ResolveInfo> list = pm.queryIntentActivities(a, 0);
                for (ResolveInfo r : list) {
                    if (r.activityInfo != null && r.activityInfo.packageName.contains("anthropic")) {
                        a.setClassName(r.activityInfo.packageName, r.activityInfo.name);
                        break;
                    }
                }
                ctx.startActivity(a);
                return true;
            } catch (Exception e) {
                lastError = "어시스턴트 열기 실패: " + e.getMessage();
            }
        }
        try {
            Intent l = pm.getLaunchIntentForPackage("com.anthropic.claude");
            if (l != null) {
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(l);
                return true;
            }
            lastError = "Claude 앱을 찾지 못했습니다";
        } catch (Exception e) {
            lastError = "Claude 열기 실패: " + e.getMessage();
        }
        return false;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
