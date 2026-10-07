package com.libfriend.teamtasks;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 홈 화면 위젯: 앱이 저장해 둔 14일치 요약 중 오늘 것을 보여줌 */
public class TaskWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) render(context, manager, id);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, TaskWidget.class));
        for (int id : ids) render(context, manager, id);
    }

    private static void render(Context context, AppWidgetManager manager, int id) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String title = "LF 팀 할일";
        String body = "앱을 열어 할일을 추가하세요";
        try {
            SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            String raw = prefs.getString("widget", null);
            if (raw != null) {
                JSONObject days = new JSONObject(raw).getJSONObject("days");
                if (days.has(today)) {
                    JSONObject d = days.getJSONObject(today);
                    title = d.optString("title", title);
                    JSONArray lines = d.optJSONArray("lines");
                    if (lines == null || lines.length() == 0) {
                        body = "오늘 할 일이 없습니다";
                    } else {
                        StringBuilder sb = new StringBuilder();
                        int max = Math.min(lines.length(), 8);
                        for (int i = 0; i < max; i++) {
                            if (i > 0) sb.append('\n');
                            sb.append("• ").append(lines.getString(i));
                        }
                        if (lines.length() > max) sb.append("\n외 ").append(lines.length() - max).append("건");
                        body = sb.toString();
                    }
                } else {
                    body = "앱을 한 번 열면 오늘 일정이 표시됩니다";
                }
            }
        } catch (Exception ignored) { }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_tasks);
        views.setTextViewText(R.id.widget_title, title);
        views.setTextViewText(R.id.widget_body, body);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        manager.updateAppWidget(id, views);
    }
}
