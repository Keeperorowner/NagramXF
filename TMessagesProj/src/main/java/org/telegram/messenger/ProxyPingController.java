package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;

public class ProxyPingController {

    private static final ProxyPingController INSTANCE = new ProxyPingController();

    private final Runnable pingRunnable = this::doPing;

    private void doPing() {
        SharedConfig.ProxyInfo proxyInfo;
        if (SharedConfig.isProxyEnabled() && (proxyInfo = SharedConfig.currentProxy) != null) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(
                    proxyInfo.address, proxyInfo.port,
                    proxyInfo.username, proxyInfo.password, proxyInfo.secret,
                    new RequestTimeDelegate() {
                        @Override
                        public void run(long time) {
                            AndroidUtilities.runOnUIThread(() -> onPingResult(proxyInfo, time));
                        }
                    }
            );
        } else {
            scheduleNextPing();
        }
    }

    private void onPingResult(SharedConfig.ProxyInfo proxyInfo, long time) {
        if (time != -1) {
            proxyInfo.ping = time;
            proxyInfo.availableCheckTime = System.currentTimeMillis();
            proxyInfo.available = true;
        } else {
            proxyInfo.available = false;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyPingUpdated, time);
        scheduleNextPing();
    }

    private void scheduleNextPing() {
        AndroidUtilities.cancelRunOnUIThread(pingRunnable);
        AndroidUtilities.runOnUIThread(pingRunnable, 10000L);
    }

    public static void init() {
        INSTANCE.scheduleNextPing();
    }
}
