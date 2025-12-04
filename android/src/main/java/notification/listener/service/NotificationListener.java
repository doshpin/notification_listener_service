package notification.listener.service;

import static notification.listener.service.models.ActionCache.cachedNotifications;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import androidx.annotation.RequiresApi;

import notification.listener.service.models.Action;


@SuppressLint("OverrideAbstract")
@RequiresApi(api = VERSION_CODES.JELLY_BEAN_MR2)
public class NotificationListener extends NotificationListenerService {
    private static NotificationListener instance;
    private NotificationQueue notificationQueue;

    public static NotificationListener getInstance() {
        return instance;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        notificationQueue = new NotificationQueue(this);
        notificationQueue.startProcessing();
        Log.i("NotificationListener", "Listener connected, queue started");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (notificationQueue != null) {
            notificationQueue.stopProcessing();
        }
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        handleNotification(notification, false);
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        handleNotification(sbn, true);
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    private void handleNotification(StatusBarNotification notification, boolean isRemoved) {
        String packageName = notification.getPackageName();
        Bundle extras = notification.getNotification().extras;
        long timestamp = notification.getNotification().when;
        Action action = NotificationUtils.getQuickReplyAction(notification.getNotification(), packageName);

        if (action != null) {
            cachedNotifications.put(notification.getId(), action);
        }

        NotificationData data = new NotificationData();
        data.packageName = packageName;
        data.id = notification.getId();
        data.key = notification.getKey();
        data.timestamp = timestamp;
        data.isRemoved = isRemoved;
        data.canReply = (action != null);

        if (extras != null) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            data.title = title == null ? null : title.toString();
            data.content = text == null ? null : text.toString();
        }

        if (notificationQueue != null) {
            notificationQueue.enqueue(data);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public List<Map<String, Object>> getActiveNotificationData() {
        List<Map<String, Object>> notificationList = new ArrayList<>();
        StatusBarNotification[] activeNotifications = getActiveNotifications();

        for (StatusBarNotification sbn : activeNotifications) {
            Map<String, Object> notifData = new HashMap<>();
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            notifData.put("id", sbn.getId());
            notifData.put("packageName", sbn.getPackageName());
            notifData.put("title", extras.getCharSequence(Notification.EXTRA_TITLE) != null
                    ? extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                    : null);
            notifData.put("content", extras.getCharSequence(Notification.EXTRA_TEXT) != null
                    ? extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                    : null);
            boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            notifData.put("onGoing", isOngoing);

            notificationList.add(notifData);
        }
        return notificationList;
    }

    // Inner class for notification data
    private static class NotificationData {
        String packageName;
        int id;
        String key;
        long timestamp;
        boolean isRemoved;
        boolean canReply;
        String title;
        String content;

        Intent toIntent() {
            Intent intent = new Intent(NotificationConstants.INTENT);
            intent.putExtra(NotificationConstants.PACKAGE_NAME, packageName);
            intent.putExtra(NotificationConstants.ID, id);
            intent.putExtra(NotificationConstants.KEY, key);
            intent.putExtra(NotificationConstants.CAN_REPLY, canReply);
            intent.putExtra(NotificationConstants.NOTIFICATION_TIMESTAMP, timestamp);
            intent.putExtra(NotificationConstants.NOTIFICATION_TITLE, title);
            intent.putExtra(NotificationConstants.NOTIFICATION_CONTENT, content);
            intent.putExtra(NotificationConstants.IS_REMOVED, isRemoved);
            return intent;
        }
    }

    // Inner class for rate-limited queue
    private static class NotificationQueue {
        private final LinkedBlockingQueue<NotificationData> queue;
        private long lastProcessedTimestamp = 0;
        private final Handler handler;
        private final Context context;
        private static final int RATE_LIMIT_MS = 100; // 10 events/sec
        private static final int MAX_QUEUE_SIZE = 1000;
        private final Runnable processingRunnable;
        private boolean isProcessing = false;

        NotificationQueue(Context context) {
            this.queue = new LinkedBlockingQueue<>();
            this.handler = new Handler(Looper.getMainLooper());
            this.context = context;
            this.processingRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isProcessing) {
                        processNextBatch();
                        handler.postDelayed(this, RATE_LIMIT_MS);
                    }
                }
            };
        }

        void enqueue(NotificationData data) {
            // Always allow removed notifications (they use old timestamp)
            if (!data.isRemoved) {
                // Allow notifications with same or newer timestamp (for updates to existing notifications)
                // Only skip if significantly older (more than 1 minute old compared to last processed)
                if (data.timestamp < lastProcessedTimestamp - 60000) {
                    Log.d("NotificationQueue", "Skipping very old notification: " + data.timestamp + " vs " + lastProcessedTimestamp);
                    return;
                }
            }

            // Drop oldest if queue is full
            if (queue.size() >= MAX_QUEUE_SIZE) {
                Log.w("NotificationQueue", "Queue full (" + MAX_QUEUE_SIZE + "), dropping oldest");
                queue.poll();
            }

            queue.offer(data);
            Log.d("NotificationQueue", "Enqueued notification, queue size: " + queue.size());
        }

        void startProcessing() {
            isProcessing = true;
            handler.post(processingRunnable);
            Log.i("NotificationQueue", "Started processing queue");
        }

        void stopProcessing() {
            isProcessing = false;
            handler.removeCallbacks(processingRunnable);
            Log.i("NotificationQueue", "Stopped processing queue");
        }

        void processNextBatch() {
            NotificationData data = queue.poll();
            if (data == null) {
                return;
            }

            // Process all notifications with same timestamp
            long currentTimestamp = data.timestamp;
            List<NotificationData> batch = new ArrayList<>();
            batch.add(data);

            // Collect all notifications with the same timestamp
            while (!queue.isEmpty()) {
                NotificationData peek = queue.peek();
                if (peek != null && peek.timestamp == currentTimestamp) {
                    batch.add(queue.poll());
                } else {
                    break;
                }
            }

            // Send all broadcasts in batch
            Log.d("NotificationQueue", "Processing batch of " + batch.size() + " notifications at timestamp " + currentTimestamp);
            for (NotificationData item : batch) {
                context.sendBroadcast(item.toIntent());
            }

            // Update timestamp after processing entire batch (only if newer)
            if (currentTimestamp > lastProcessedTimestamp) {
                lastProcessedTimestamp = currentTimestamp;
            }
        }
    }
}
