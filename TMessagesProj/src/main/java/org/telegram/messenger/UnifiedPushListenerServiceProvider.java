package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;

import org.unifiedpush.android.connector.UnifiedPush;

public class UnifiedPushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {
    private static final String INSTANCE = "default";

    // Use the built-in FCM distributor instead of a distributor app
    private final boolean fcm;

    public UnifiedPushListenerServiceProvider(boolean fcm) {
        this.fcm = fcm;
    }

    @Override
    public boolean hasServices() {
        Context context = ApplicationLoader.applicationContext;
        if (fcm) {
            return UnifiedPushFcmDistributor.isAvailable(context);
        }
        for (String distributor : UnifiedPush.getDistributors(context)) {
            if (!context.getPackageName().equals(distributor)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public void onRequestPushToken() {
        if (!TextUtils.isEmpty(SharedConfig.pushString)) {
            FileLog.d("UnifiedPush endpoint found"); // the Web Push token carries the auth secret
        } else {
            FileLog.d("No UnifiedPush string found");
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                SharedConfig.saveConfig();
                Context context = ApplicationLoader.applicationContext;
                String self = context.getPackageName();
                String current = UnifiedPush.getAckDistributor(context);
                if (fcm) {
                    if (!self.equals(current)) {
                        UnifiedPush.saveDistributor(context, self);
                    }
                } else if (current == null || self.equals(current)) {
                    // Our own package is listed too, as the built-in FCM distributor
                    for (String distributor : UnifiedPush.getDistributors(context)) {
                        if (!self.equals(distributor)) {
                            UnifiedPush.saveDistributor(context, distributor);
                            break;
                        }
                    }
                }
                UnifiedPush.register(
                        ApplicationLoader.applicationContext,
                        INSTANCE,
                        "Telegram Web Push",
                        null
                );
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void unregister() {
        try {
            UnifiedPush.unregister(ApplicationLoader.applicationContext, INSTANCE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_WEB;
    }
}
