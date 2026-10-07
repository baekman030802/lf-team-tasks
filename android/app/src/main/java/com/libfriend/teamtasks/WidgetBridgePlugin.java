package com.libfriend.teamtasks;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Web 화면에서 데이터를 저장한 뒤 홈 화면 위젯을 즉시 새로 그리게 하는 다리 */
@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
    @PluginMethod
    public void refresh(PluginCall call) {
        TaskWidget.updateAll(getContext());
        call.resolve();
    }
}
