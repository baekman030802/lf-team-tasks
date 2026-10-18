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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

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
    static volatile int wordCount = 0;      // "자비스" 인식 횟수
    static volatile String modelState = ""; // "", "받는 중 N%", "푸는 중", "오류: ..."
    static volatile boolean reload = false; // 설정 바뀜 → 음성 인식 다시 준비
    static final String URL_KO = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"; // 82MB
    static final String URL_EN = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"; // 40MB
    /** 호출어에 영어 글자가 있으면 영어 모델, 아니면 한국어 모델 */
    static String lang(Context c) {
        String w = c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("word", "자비스");
        return w.matches(".*[A-Za-z].*") ? "en" : "ko";
    }
    // 호출어: "자비스" (+ 비슷하게 들리는 말)
    static final Pattern WAKE = Pattern.compile("자비스|자비쓰|자빗스|쟈비스|재비스|잡이스|자비즈|자비수");
    static volatile String lastHeard = "";  // 테스트용: 마지막으로 알아들은 말

    /** 한글을 자모로 풀기 (예: 자 → ㅈㅏ). 비슷한 발음 비교용 */
    static String jamo(String t) {
        final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
        final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int x = c - 0xAC00;
                b.append(CHO.charAt(x / 588)).append(JUNG.charAt((x % 588) / 28));
                if (x % 28 != 0) b.append(JONG.charAt(x % 28));
            }
        }
        return b.toString();
    }

    static int dist(String a, String b) {
        int[] p = new int[b.length() + 1], q = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            q[0] = i;
            for (int j = 1; j <= b.length(); j++)
                q[j] = Math.min(Math.min(q[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = q; q = t;
        }
        return p[b.length()];
    }

    static volatile String wakeWord = "자비스";   // 설정에서 바꿀 수 있는 호출어

    /** 호출어와 발음이 비슷한 말이 들어 있는지 (자모 1개 차이까지, 짧은 단어는 정확히) */
    static boolean heardWake(String text) {
        if (wakeWord.matches(".*[A-Za-z].*")) {   // 영어 호출어: 단어 단위로 비교 (5글자 이상이면 1글자 차이 허용)
            String w = wakeWord.toLowerCase().replaceAll("[^a-z]", "");
            String[] ws = text.toLowerCase().replaceAll("[^a-z ]", " ").trim().split("\\s+");
            for (int i = 0; i < ws.length; i++) {
                String a = ws[i], ab = i + 1 < ws.length ? a + ws[i + 1] : a;
                if (a.equals(w) || ab.equals(w)) return true;
                if (w.length() >= 5 && (dist(a, w) <= 1 || dist(ab, w) <= 1)) return true;
            }
            return false;
        }
        String t = text.replace(" ", "");
        String w = wakeWord.replace(" ", "");
        if (w.isEmpty()) return false;
        if (t.contains(w)) return true;
        if ("자비스".equals(w) && WAKE.matcher(t).find()) return true;
        String j = jamo(t), WAKE_J = jamo(w);
        int L = WAKE_J.length();
        if (L < 6) return false;   // 자모 6개 미만(예: 두 글자 단어)은 오작동이 많아 정확히 같을 때만
        for (int len = L - 1; len <= L + 1; len++)
            for (int i = 0; i + len <= j.length(); i++)
                if (dist(j.substring(i, i + len), WAKE_J) <= 1) return true;
        return false;
    }

    /** Vosk 결과 JSON에서 글자만 꺼내기 */
    static String voskText(String js) {
        int k = js.indexOf(":");
        if (k < 0) return "";
        int a = js.indexOf('"', k + 1), b = js.lastIndexOf('"');
        return (a >= 0 && b > a) ? js.substring(a + 1, b).trim() : "";
    }

    static File modelDir(Context c) { return new File(c.getFilesDir(), "vosk-" + lang(c)); }
    static boolean modelReady(Context c) { File d = modelDir(c); return new File(d, "am").isDirectory() || new File(d, "conf").isDirectory(); }
    // 박수는 폰 내려놓는 소리에 잘못 반응해서 뺌 (사용자 요청) → 항상 호출어만
    static String trig(Context c) { return "word"; }

    /** 한국어 음성 모델(약 82MB)을 받아서 앱 안에 풀기 */
    static void downloadModel(final Context ctx) {
        if (modelState.startsWith("받는") || modelState.startsWith("푸는")) return;
        final Context c = ctx.getApplicationContext();
        modelState = "받는 중 0%";
        new Thread(() -> {
            File zip = new File(c.getCacheDir(), "vosk-ko.zip");
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("en".equals(lang(c)) ? URL_EN : URL_KO).openConnection();
                con.setConnectTimeout(20000); con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                long total = con.getContentLengthLong(), got = 0;
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(zip)) {
                    byte[] b = new byte[65536]; int r;
                    while ((r = in.read(b)) > 0) {
                        out.write(b, 0, r); got += r;
                        if (total > 0) modelState = "받는 중 " + (got * 100 / total) + "%";
                    }
                }
                modelState = "푸는 중";
                File dir = modelDir(c), tmp = new File(c.getFilesDir(), "vosk-tmp");
                deleteAll(tmp); tmp.mkdirs();
                String base = tmp.getCanonicalPath() + File.separator;
                try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
                    ZipEntry ze; byte[] b = new byte[65536];
                    while ((ze = zin.getNextEntry()) != null) {
                        String name = ze.getName();
                        int slash = name.indexOf('/');
                        if (slash >= 0) name = name.substring(slash + 1);   // 맨 위 폴더 이름 빼기
                        if (name.isEmpty()) continue;
                        File f = new File(tmp, name);
                        if (!f.getCanonicalPath().startsWith(base)) continue;
                        if (ze.isDirectory()) { f.mkdirs(); continue; }
                        File par = f.getParentFile(); if (par != null) par.mkdirs();
                        try (FileOutputStream out = new FileOutputStream(f)) { int r; while ((r = zin.read(b)) > 0) out.write(b, 0, r); }
                    }
                }
                deleteAll(dir);
                if (!tmp.renameTo(dir)) throw new Exception("폴더 이동 실패");
                zip.delete();
                modelState = "";
                reload = true;
            } catch (Exception e) {
                zip.delete();
                modelState = "오류: " + e.getMessage();
            }
        }, "lf-vosk-dl").start();
    }

    static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles(); if (l != null) for (File x : l) deleteAll(x);
        f.delete();
    }

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
        wakeWord = getSharedPreferences(PREFS, MODE_PRIVATE).getString("word", "자비스");
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
                .setContentText("\"" + wakeWord + "\" → Claude")
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
        Model vModel = null; Recognizer vRec = null; String loadedLang = "";
        reload = true;

        int zeroFrames = 0, failCount = 0;
        while (!stop) {
            int n = rec.read(buf, 0, FRAME);
            // Claude가 마이크를 쓰고 나면 녹음이 끊기거나(오류) 소리가 0으로만 들어올 수 있음 → 마이크 다시 열기
            boolean dead = n < 0 || rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING;
            if (!dead && n > 0) {
                boolean silent = true;
                for (int i = 0; i < n; i++) if (buf[i] != 0) { silent = false; break; }
                zeroFrames = silent ? zeroFrames + 1 : 0;
            }
            if (dead || zeroFrames > 190) {          // 오류 또는 3초 넘게 완전한 0
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
                try { Thread.sleep(dead ? 800 : 300); } catch (InterruptedException ignored) {}
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
                    rec.startRecording();
                    failCount = 0;
                } catch (Exception ex) {
                    lastError = "마이크 다시 열기 실패: " + ex.getMessage();
                    if (++failCount > 20) { new Handler(Looper.getMainLooper()).post(this::stopSelf); break; }
                }
                zeroFrames = 0;
                if (vRec != null) vRec.reset();
                continue;
            }
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
            // 설정에 따라 "자비스" 음성 인식 준비/해제
            if (reload) {
                reload = false;
                wakeWord = getSharedPreferences(PREFS, MODE_PRIVATE).getString("word", "자비스");
                String t = trig(this);
                boolean wantWord = !"clap".equals(t) && modelReady(this);
                String lg = lang(this);
                if (vModel != null && !lg.equals(loadedLang)) { if (vRec != null) { vRec.close(); vRec = null; } vModel.close(); vModel = null; }
                if (!wantWord && vRec != null) { vRec.close(); vRec = null; }
                if (!wantWord && vModel != null) { vModel.close(); vModel = null; }
                if (wantWord && vRec == null) {
                    try {
                        LibVosk.setLogLevel(LogLevel.WARNINGS);
                        if (vModel == null) { vModel = new Model(modelDir(this).getAbsolutePath()); loadedLang = lg; }
                        vRec = new Recognizer(vModel, (float) RATE);
                    } catch (Throwable ex) { lastError = "음성 모델 열기 실패: " + ex.getMessage(); vRec = null; }
                }
            }
            boolean useClap = false;
            if (frameNo < cooldownUntil) { bg = bg * 0.98 + e * 0.02; continue; }
            if (vRec != null) {
                boolean fin = vRec.acceptWaveForm(buf, n);
                if (fin || frameNo % 6 == 0) {
                    String js = fin ? vRec.getResult() : vRec.getPartialResult();
                    String heard = voskText(js);
                    if (!heard.isEmpty()) lastHeard = heard;
                    if (heardWake(heard)) {
                        vRec.reset();
                        wordCount++;
                        cooldownUntil = frameNo + 8000 / 16;
                        triggerCount++;
                        lastTriggerAt = System.currentTimeMillis();
                        new Handler(Looper.getMainLooper()).post(this::wakeClaude);
                        continue;
                    }
                }
            }
            if (!useClap) continue;

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
                        cooldownUntil = frameNo + 8000 / 16;  // 8초 동안 무시 (같은 말로 두 번 뜨지 않게)
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
        try { if (vRec != null) vRec.close(); if (vModel != null) vModel.close(); } catch (Throwable ignored) {}
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
                wl.acquire(8000);
            }
        } catch (Exception ignored) {}
        // 화면 켜기·잠금 해제 요청은 투명 화면(WakeActivity)이 맡고, 거기서 Claude를 띄움
        try {
            Intent w = new Intent(this, WakeActivity.class);
            w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(w);
        } catch (Exception e) {
            lastError = "화면 켜기 실패: " + e.getMessage();
            launchAssistant(this);
        }
    }

    static boolean launchAssistant(Context ctx) { return launchAssistant(ctx, null); }

    static boolean launchAssistant(Context ctx, android.os.Bundle opts) {
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
                ctx.startActivity(a, opts);
                return true;
            } catch (Exception e) {
                lastError = "어시스턴트 열기 실패: " + e.getMessage();
            }
        }
        try {
            Intent l = pm.getLaunchIntentForPackage("com.anthropic.claude");
            if (l != null) {
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(l, opts);
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
