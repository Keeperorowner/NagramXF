package tw.nekomimi.nekogram.helpers;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import tw.nekomimi.nekogram.NekoConfig;

public class JoinOfficialChannelHelper {

    private static final String CHANNEL_USERNAME = "NagramXF";
    private static final String CHANNEL_URL = "https://t.me/" + CHANNEL_USERNAME;
    private static final String SHOWN_KEY = "first_join_official_channel_shown";

    public static void postCheck(Context ctx, int currentAccount) {
        if (ctx == null) return;
        if (NekoConfig.getPreferences().getBoolean(SHOWN_KEY, false)) return;

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = CHANNEL_USERNAME;
        connectionsManager.sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) return;
            TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            TLRPC.Chat target = null;
            for (TLRPC.Chat c : res.chats) {
                if (CHANNEL_USERNAME.equals(c.username)) {
                    target = c;
                    break;
                }
            }
            if (target == null) return;
            messagesController.putChats(res.chats, false);
            MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, false, true);

            final TLRPC.Chat chat = target;
            if (!chat.left || chat.kicked) {
                markShown();
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (NekoConfig.getPreferences().getBoolean(SHOWN_KEY, false)) return;
                markShown();

                AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
                builder.setTitle(LocaleController.getString(R.string.JoinOfficialChannelTitle));
                builder.setMessage(LocaleController.getString(R.string.JoinOfficialChannelMessage));
                builder.setPositiveButton(LocaleController.getString(R.string.ChannelJoin), (d, w) -> {
                    messagesController.addUserToChat(
                            chat.id,
                            UserConfig.getInstance(currentAccount).getCurrentUser(),
                            0, null, null, null);
                    Browser.openUrl(ctx, CHANNEL_URL);
                });
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                try {
                    builder.show();
                } catch (Exception ignored) {
                }
            });
        });
    }

    private static void markShown() {
        NekoConfig.getPreferences().edit().putBoolean(SHOWN_KEY, true).apply();
    }
}
