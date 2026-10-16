package com.libfriend.teamtasks;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * 박수 인식 뒤 잠깐 뜨는 투명 화면.
 * 꺼진 화면을 켜고, 잠겨 있으면 잠금 해제(지문·얼굴)를 요청한 뒤 Claude를 띄우고 바로 닫힘.
 */
public class WakeActivity extends Activity {
    private boolean launched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked() && Build.VERSION.SDK_INT >= 26) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { go(); }
                @Override public void onDismissCancelled() { finishSoon(); }
                @Override public void onDismissError() { go(); }
            });
        } else {
            go();
        }
    }

    private void go() {
        if (launched) return;
        launched = true;
        Bundle opts = null;
        try {
            // 접힌 상태(커버 화면)에서 떴다면 같은 화면에 Claude를 띄움
            if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
                ActivityOptions ao = ActivityOptions.makeBasic();
                ao.setLaunchDisplayId(getDisplay().getDisplayId());
                opts = ao.toBundle();
            }
        } catch (Exception ignored) {}
        ClapService.launchAssistant(this, opts);
        finishSoon();
    }

    private void finishSoon() {
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 600);
    }
}
