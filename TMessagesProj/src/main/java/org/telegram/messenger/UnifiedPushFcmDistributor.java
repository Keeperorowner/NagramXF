package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import org.unifiedpush.android.embedded_fcm_distributor.EmbeddedDistributorReceiver;
import org.unifiedpush.android.embedded_fcm_distributor.Gateway;

/**
 * UnifiedPush distributor on Play Services FCM, independent of api_id. FCM requires VAPID-signed
 * pushes and Telegram does not sign, so Telegram gets the gateway's /fcm route, which signs them.
 */
public class UnifiedPushFcmDistributor extends EmbeddedDistributorReceiver {

    private static final Gateway GATEWAY = new Gateway() {
        @NonNull
        @Override
        public String getVapid() {
            return UnifiedPushService.getFcmVapidKey();
        }

        @NonNull
        @Override
        public String getEndpoint(@NonNull String token) {
            return UnifiedPushService.getFcmEndpoint(token);
        }
    };

    @NonNull
    @Override
    public Gateway getGateway() {
        return GATEWAY;
    }

    public static boolean isAvailable(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
