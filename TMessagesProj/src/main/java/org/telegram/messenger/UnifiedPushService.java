package org.telegram.messenger;

import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.telegram.tgnet.ConnectionsManager;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import it.belloworld.mercurygram.WebPushDecryptor;
import xyz.nextalone.nagram.NaConfig;

public class UnifiedPushService extends PushService {

    public static final String UP_GATEWAY_DEFAULT = "https://p2p.belloworld.it/"; // https://github.com/Mercurygram/Mercurygram?tab=readme-ov-file#unifiedpush-put-to-post-gateway
    // VAPID public key of UP_GATEWAY_DEFAULT, from Mercurygram's MgEmbeddedFcmDistributor
    public static final String UP_FCM_VAPID_DEFAULT = "BOocuINYMsroo0cng_bA3B1AhDGnfxkGuYE_J_gH5G3w_Ek1t_kAOXA8CZS1WtenzRFaGMwnTKGQ7Hp4h3Dmw1g";

    private static final String UP_FAILED = "__UNIFIEDPUSH_FAILED__";

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;
    private static long numDecryptSuccess = 0;
    private static long numDecryptFailed = 0;

    public static volatile byte[] webPushPrivateKey;    // PKCS#8-encoded P-256 private key
    public static volatile byte[] webPushPublicKey;     // Raw 65-byte uncompressed P-256 point (04||X||Y)
    public static volatile byte[] webPushAuthSecret;    // 16-byte random auth secret

    public static String getGateway() {
        String gateway = NaConfig.INSTANCE.getPushServiceTypeUnifiedGateway().String();
        if (gateway.isEmpty()) {
            return UP_GATEWAY_DEFAULT;
        }
        return gateway.endsWith("/") ? gateway : gateway + "/";
    }

    public static String getFcmVapidKey() {
        String key = NaConfig.INSTANCE.getPushServiceTypeUnifiedFcmVapidKey().String();
        return key.isEmpty() ? UP_FCM_VAPID_DEFAULT : key;
    }

    /** Endpoint the embedded FCM distributor hands out: the gateway signs pushes to it with its VAPID key. */
    public static String getFcmEndpoint(String token) {
        return getGateway() + "fcm/" + token;
    }

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    public static long getNumDecryptSuccess() {
        return numDecryptSuccess;
    }

    public static long getNumDecryptFailed() {
        return numDecryptFailed;
    }

    public static synchronized void loadWebPushKeys() {
        if (webPushPrivateKey != null && webPushPublicKey != null && webPushAuthSecret != null) {
            return;
        }

        String wpPriv = NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushPrivateKey().String();
        if (!TextUtils.isEmpty(wpPriv)) webPushPrivateKey = Base64.decode(wpPriv, Base64.DEFAULT);
        String wpPub = NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushPublicKey().String();
        if (!TextUtils.isEmpty(wpPub)) webPushPublicKey = Base64.decode(wpPub, Base64.DEFAULT);
        String wpAuth = NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushAuthSecret().String();
        if (!TextUtils.isEmpty(wpAuth)) webPushAuthSecret = Base64.decode(wpAuth, Base64.DEFAULT);
    }

    public static synchronized void saveWebPushKeys() {
        if (webPushPrivateKey == null || webPushPublicKey == null || webPushAuthSecret == null) {
            return;
        }
        NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushPrivateKey().setConfigString(Base64.encodeToString(webPushPrivateKey, Base64.DEFAULT));
        NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushPublicKey().setConfigString(Base64.encodeToString(webPushPublicKey, Base64.DEFAULT));
        NaConfig.INSTANCE.getPushServiceTypeUnifiedWebPushAuthSecret().setConfigString(Base64.encodeToString(webPushAuthSecret, Base64.DEFAULT));
    }

    public static synchronized void ensureWebPushKeys() {
        loadWebPushKeys();
        if (webPushPrivateKey != null && webPushPublicKey != null && webPushAuthSecret != null) {
            return;
        }
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
            kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair keyPair = kpg.generateKeyPair();
            java.security.interfaces.ECPublicKey ecPub = (java.security.interfaces.ECPublicKey) keyPair.getPublic();

            webPushPublicKey = WebPushDecryptor.extractRawPublicKey(ecPub);
            webPushPrivateKey = keyPair.getPrivate().getEncoded(); // PKCS#8
            byte[] secret = new byte[16];
            new java.security.SecureRandom().nextBytes(secret);
            webPushAuthSecret = secret;
        } catch (Exception e) {
            FileLog.e(e);
        }
        saveWebPushKeys();
    }

    @Override
    public void onNewEndpoint(@NonNull PushEndpoint endpoint, @NonNull String instance) {
        if (!(PushListenerController.getProvider() instanceof UnifiedPushListenerServiceProvider)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            // Ensure WebPush ECDH keys exist before registering
            ensureWebPushKeys();

            // All distributors route through the /aesgcm gateway which serializes
            // WebPush headers into the body (common-proxies compatible format)
            String gateway = getGateway();
            // The embedded FCM distributor already points at the gateway's /fcm route, which
            // folds the headers itself and signs the push for FCM.
            boolean fcm = endpoint.getUrl().startsWith(getFcmEndpoint(""));

            try {
                String gatewayUrl = fcm
                        ? endpoint.getUrl()
                        : gateway + "aesgcm?e=" + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());

                // WebPush JSON token: endpoint + client keys for Telegram to encrypt payloads
                String p256dh = android.util.Base64.encodeToString(webPushPublicKey,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                String auth = android.util.Base64.encodeToString(webPushAuthSecret,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);

                // Simple Push (token_type=4) wakes the app for events Telegram sends without content
                // (e.g. secret chats); registerForPush() registers it after the Web Push token.
                String simplePushUrl = fcm
                        ? endpoint.getUrl()
                        : gateway + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());
                NaConfig.INSTANCE.getPushServiceTypeUnifiedSimple().setConfigString(simplePushUrl);

                org.json.JSONObject keys = new org.json.JSONObject();
                keys.put("p256dh", p256dh);
                keys.put("auth", auth);
                org.json.JSONObject tokenObj = new org.json.JSONObject();
                tokenObj.put("endpoint", gatewayUrl);
                tokenObj.put("keys", keys);
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, tokenObj.toString());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    @Override
    public void onMessage(@NonNull PushMessage message, @NonNull String instance) {
        final long receiveTime = SystemClock.elapsedRealtime();

        lastReceivedNotification = receiveTime;
        numOfReceivedNotifications++;

        // Held until processing ends; not reference-counted, so a release after the timeout is a no-op
        PowerManager.WakeLock wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nagram:wp");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(30_000);

        // A push may cold-start the process, before any endpoint callback loaded the keys
        loadWebPushKeys();

        // Try WebPush decryption first
        if (webPushPrivateKey != null && webPushPublicKey != null && webPushAuthSecret != null) {
            try {
                byte[] plaintext = WebPushDecryptor.decrypt(
                        message.getContent(),
                        webPushPrivateKey,
                        webPushPublicKey,
                        webPushAuthSecret
                );
                // Decrypted payload is JSON {"p":"<base64url-mtproto>"}, same as FCM
                org.json.JSONObject payloadJson = new org.json.JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                String encoded = payloadJson.getString("p");
                numDecryptSuccess++;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP START PROCESSING (decrypted)");
                }
                // processRemoteMessage() blocks until processing ends: keep it off the main thread
                // and shared queues. Time is epoch, it becomes messageOwner.date.
                new Thread(() -> {
                    try {
                        PushListenerController.processRemoteMessage(
                                PushListenerController.PUSH_TYPE_WEB, encoded, System.currentTimeMillis());
                    } finally {
                        wakeLock.release();
                    }
                }, "WebPush").start();
                return;
            } catch (Exception e) {
                numDecryptFailed++;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP DECRYPT ERROR, falling back to wake-up: " + e.getMessage());
                }
                // Fall through to wake-up behavior
            }
        }

        // Fallback: wake up the app to fetch updates via MTProto
        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP PRE INIT APP");
            }
            ApplicationLoader.postInitApplication();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP POST INIT APP");
            }
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("UP START PROCESSING (wake-up fallback)");
                    }
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            ConnectionsManager.onInternalPushReceived(a);
                            ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                        }
                    }
                } finally {
                    wakeLock.release();
                }
            });
        });
    }

    @Override
    public void onRegistrationFailed(@NonNull FailedReason reason, @NonNull String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Failed to get endpoint: " + reason);
        }
        SharedConfig.pushStringStatus = UP_FAILED;
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
            PushListenerController.unregisterSimplePush();
        });
    }

    @Override
    public void onUnregistered(@NonNull String instance) {
        SharedConfig.pushStringStatus = UP_FAILED;
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
            PushListenerController.unregisterSimplePush();
        });
    }
}
