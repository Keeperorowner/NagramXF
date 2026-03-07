package tw.nekomimi.nekogram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;

import java.util.LinkedList;

public class BackButtonMenuRecent {

    private static final int MAX_RECENT_DIALOGS = 1000;

    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekorecentdialogs", Context.MODE_PRIVATE);
    private static final SparseArray<LinkedList<Long>> recentDialogs = new SparseArray<>();

    public static LinkedList<Long> getRecentDialogs(int currentAccount) {
        LinkedList<Long> recentDialog = recentDialogs.get(currentAccount);
        if (recentDialog == null) {
            recentDialog = new LinkedList<>();
            String list = preferences.getString("recents_" + currentAccount, null);
            if (!TextUtils.isEmpty(list)) {
                byte[] bytes = Base64.decode(list, Base64.NO_WRAP | Base64.NO_PADDING);
                SerializedData data = new SerializedData(bytes);
                int count = data.readInt32(false);
                for (int a = 0; a < count; a++) {
                    recentDialog.add(data.readInt64(false));
                }
                data.cleanup();
            }
            recentDialogs.put(currentAccount, recentDialog);
        }
        return recentDialog;
    }

    public static void addToRecentDialogs(int currentAccount, long dialogId) {
        LinkedList<Long> recentDialog = getRecentDialogs(currentAccount);
        for (int i = 0; i < recentDialog.size(); i++) {
            if (recentDialog.get(i) == dialogId) {
                recentDialog.remove(i);
                break;
            }
        }

        if (recentDialog.size() >= MAX_RECENT_DIALOGS) {
            recentDialog.removeLast();
        }
        recentDialog.addFirst(dialogId);
        LinkedList<Long> finalRecentDialog = new LinkedList<>(recentDialog);
        Utilities.globalQueue.postRunnable(() -> saveRecentDialogs(currentAccount, finalRecentDialog));
    }

    public static void saveRecentDialogs(int currentAccount, LinkedList<Long> recentDialog) {
        SerializedData serializedData = new SerializedData();
        int count = recentDialog.size();
        serializedData.writeInt32(count);
        for (Long dialog : recentDialog) {
            serializedData.writeInt64(dialog);
        }
        preferences.edit().putString("recents_" + currentAccount, Base64.encodeToString(serializedData.toByteArray(), Base64.NO_WRAP | Base64.NO_PADDING)).apply();
        serializedData.cleanup();
    }

    public static void clearRecentDialogs(int currentAccount) {
        getRecentDialogs(currentAccount).clear();
        preferences.edit().putString("recents_" + currentAccount, "").apply();
    }
}
