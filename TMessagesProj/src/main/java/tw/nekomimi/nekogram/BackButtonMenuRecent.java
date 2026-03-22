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

    private static final Object lock = new Object();
    private static final SparseArray<LinkedList<Long>> recentDialogs = new SparseArray<>();

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nekorecentdialogs", Context.MODE_PRIVATE);
    }

    private static LinkedList<Long> getOrLoadRecentDialogsLocked(int currentAccount) {
        LinkedList<Long> recentDialog = recentDialogs.get(currentAccount);
        if (recentDialog == null) {
            recentDialog = new LinkedList<>();
            String list = getPreferences().getString("recents_" + currentAccount, null);
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

    public static LinkedList<Long> getRecentDialogs(int currentAccount) {
        synchronized (lock) {
            return new LinkedList<>(getOrLoadRecentDialogsLocked(currentAccount));
        }
    }

    public static void addToRecentDialogs(int currentAccount, long dialogId) {
        synchronized (lock) {
            LinkedList<Long> recentDialog = getOrLoadRecentDialogsLocked(currentAccount);
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
            LinkedList<Long> snapshot = new LinkedList<>(recentDialog);
            Utilities.globalQueue.postRunnable(() -> persistRecentDialogs(currentAccount, snapshot));
        }
    }

    public static void saveRecentDialogs(int currentAccount, LinkedList<Long> recentDialog) {
        synchronized (lock) {
            recentDialogs.put(currentAccount, new LinkedList<>(recentDialog));
            LinkedList<Long> snapshot = new LinkedList<>(recentDialog);
            Utilities.globalQueue.postRunnable(() -> persistRecentDialogs(currentAccount, snapshot));
        }
    }

    private static void persistRecentDialogs(int currentAccount, LinkedList<Long> snapshot) {
        SerializedData serializedData = new SerializedData();
        int count = snapshot.size();
        serializedData.writeInt32(count);
        for (Long dialog : snapshot) {
            serializedData.writeInt64(dialog);
        }
        getPreferences().edit().putString("recents_" + currentAccount, Base64.encodeToString(serializedData.toByteArray(), Base64.NO_WRAP | Base64.NO_PADDING)).apply();
        serializedData.cleanup();
    }

    public static void clearRecentDialogs(int currentAccount) {
        synchronized (lock) {
            LinkedList<Long> list = recentDialogs.get(currentAccount);
            if (list != null) {
                list.clear();
            }
        }
        getPreferences().edit().remove("recents_" + currentAccount).apply();
    }
}
