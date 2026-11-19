package com.maeyaion.pos.core.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** Redacted backup lifecycle notifications; never includes account tokens or recovery material. */
public final class BackupNotifications {
    private static final String CHANNEL_ID = "backup_status";
    private static final int NOTIFICATION_ID = 7301;

    private BackupNotifications() {}

    public static void showFailure(Context context, String message) {
        show(context, "สำรองข้อมูลไม่สำเร็จ", message);
    }

    public static void showSuccess(Context context) {
        show(context, "สำรองข้อมูลสำเร็จ", "ยืนยันไฟล์สำรองบน Google Drive แล้ว");
    }

    public static void showVerificationSuccess(Context context) {
        show(context, "ตรวจสอบข้อมูลสำรองแล้ว", "ดาวน์โหลดและกู้คืนลงฐานข้อมูลทดสอบแยกสำเร็จ โดยไม่แตะฐานข้อมูลจริง");
    }

    public static void showFailureThrottled(Context context, String message) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                UnattendedBackupScheduler.PREFS, Context.MODE_PRIVATE);
        long last = preferences.getLong(UnattendedBackupScheduler.KEY_LAST_FAILURE, 0L);
        if (last != 0L && now - last < 6L * 60L * 60L * 1000L) return;
        preferences.edit().putLong(UnattendedBackupScheduler.KEY_LAST_FAILURE, now).apply();
        showFailure(context, message);
    }

    private static void show(Context context, String title, String message) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "สถานะการสำรองข้อมูล", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("แจ้งผลการสำรองและตรวจสอบข้อมูล POS");
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException notificationPermissionMissing) {
            // Android 13+ may deny notifications until the owner grants runtime permission.
        }
    }
}
