package notification.listener.service;

import static notification.listener.service.NotificationConstants.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION_CODES;

import androidx.annotation.RequiresApi;

import io.flutter.plugin.common.EventChannel.EventSink;

import java.util.HashMap;

public class NotificationReceiver extends BroadcastReceiver {

    private EventSink eventSink;

    public NotificationReceiver(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @RequiresApi(api = VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onReceive(Context context, Intent intent) {
        String packageName = intent.getStringExtra(PACKAGE_NAME);
        String title = intent.getStringExtra(NOTIFICATION_TITLE);
        String content = intent.getStringExtra(NOTIFICATION_CONTENT);
        boolean hasRemoved = intent.getBooleanExtra(IS_REMOVED, false);
        boolean canReply = intent.getBooleanExtra(CAN_REPLY, false);
        int id = intent.getIntExtra(ID, -1);
        String key = intent.getStringExtra(NotificationConstants.KEY);
        long timestamp = intent.getLongExtra(NotificationConstants.NOTIFICATION_TIMESTAMP, -1);

        HashMap<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("key", key);
        data.put("packageName", packageName);
        data.put("title", title);
        data.put("content", content);
        data.put("hasRemoved", hasRemoved);
        data.put("canReply", canReply);
        data.put("timestamp", timestamp);

        eventSink.success(data);
    }
}
