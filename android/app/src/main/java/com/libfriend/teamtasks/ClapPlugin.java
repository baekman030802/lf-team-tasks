package com.libfriend.teamtasks;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** 웹 화면 ↔ 비서 대기(박수 인식) 서비스 다리 */
@CapacitorPlugin(
        name = "ClapListener",
        permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic") }
)
public class ClapPlugin extends Plugin {

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(ClapService.PREFS, android.content.Context.MODE_PRIVATE);
    }

    private boolean overlayOk() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext());
    }

    private JSObject state() {
        JSObject o = new JSObject();
        o.put("running", ClapService.running);
        o.put("mic", getPermissionState("mic") == PermissionState.GRANTED);
        o.put("overlay", overlayOk());
        o.put("sens", prefs().getInt("sens", 3));
        o.put("mode", prefs().getString("mode", "assist"));
        o.put("claps", ClapService.clapCount);
        o.put("triggers", ClapService.triggerCount);
        o.put("lastTriggerAt", ClapService.lastTriggerAt);
        o.put("error", ClapService.lastError);
        return o;
    }

    @PluginMethod
    public void status(PluginCall call) { call.resolve(state()); }

    @PluginMethod
    public void requestMic(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) { call.resolve(state()); return; }
        requestPermissionForAlias("mic", call, "micCallback");
    }

    @PermissionCallback
    private void micCallback(PluginCall call) { call.resolve(state()); }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            openAppSettings(call);
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) { call.reject("마이크 권한이 필요합니다"); return; }
        ClapService.lastError = "";
        ClapService.start(getContext());
        call.resolve(state());
    }

    @PluginMethod
    public void stop(PluginCall call) {
        ClapService.stop(getContext());
        ClapService.running = false;
        call.resolve(state());
    }

    @PluginMethod
    public void setOptions(PluginCall call) {
        SharedPreferences.Editor ed = prefs().edit();
        Integer s = call.getInt("sens");
        if (s != null) ed.putInt("sens", Math.max(1, Math.min(5, s)));
        String m = call.getString("mode");
        if (m != null) ed.putString("mode", m);
        ed.apply();
        call.resolve(state());
    }

    @PluginMethod
    public void testLaunch(PluginCall call) {
        boolean ok = ClapService.launchAssistant(getContext());
        JSObject o = state();
        o.put("ok", ok);
        call.resolve(o);
    }
}
