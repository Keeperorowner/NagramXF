package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;

import com.exteragram.messenger.utils.MarkdownUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;

import tw.nekomimi.nekogram.utils.FileUtil;

public final class PluginFileViewer {
    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final int MAX_BLOCK_LENGTH = 8192;

    public static final PluginFileViewer INSTANCE = new PluginFileViewer();

    private PluginFileViewer() {
    }

    public static PluginFileViewer getInstance() {
        return INSTANCE;
    }

    public boolean open(final BaseFragment fragment, final File file, final String fileName) {
        Activity parentActivity;
        if (fragment == null || (parentActivity = fragment.getParentActivity()) == null || file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        if (file.length() > MAX_FILE_SIZE) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ImportFileTooLarge)).show();
            return false;
        }
        final AlertDialog alertDialog = new AlertDialog(parentActivity, 3, fragment.getResourceProvider());
        alertDialog.setCanceledOnTouchOutside(false);
        final boolean[] opened = {false};
        alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                opened[0] = true;
            }
        });
        alertDialog.showDelayed(150L);
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                MessageObject messageObject;
                try {
                    messageObject = createMessageObject(file, normalizeFileName(file, fileName));
                } catch (Throwable th) {
                    FileLog.e(th);
                    messageObject = null;
                }
                final MessageObject messageObjectFinal = messageObject;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        openLoadingFinished(alertDialog, opened, messageObjectFinal, fragment);
                    }
                });
            }
        });
        return true;
    }

    private void openLoadingFinished(AlertDialog alertDialog, boolean[] opened, MessageObject messageObject, BaseFragment fragment) {
        try {
            alertDialog.dismiss();
        } catch (Throwable ignored) {
        }
        if (opened[0]) {
            return;
        }
        if (messageObject != null) {
            fragment.createArticleViewer(false).open(messageObject);
        } else {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ErrorOccurred)).show();
        }
    }

    private MessageObject createMessageObject(File file, String fileName) {
        String text = FileUtil.readUtf8String(file);
        TL_iv.TL_page tL_page = new TL_iv.TL_page();
        tL_page.local = file;
        tL_page.url = fileName;
        MarkdownUtils.appendPreformattedBlocks(tL_page.blocks, text, "python", MAX_BLOCK_LENGTH);
        TLRPC.TL_webPage tL_webPage = new TLRPC.TL_webPage();
        tL_webPage.id = file.getAbsolutePath().hashCode();
        tL_webPage.url = fileName;
        tL_webPage.display_url = fileName;
        tL_webPage.title = fileName;
        tL_webPage.flags |= 1028;
        tL_webPage.cached_page = tL_page;
        long clientUserId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
        TLRPC.TL_message tL_message = new TLRPC.TL_message();
        tL_message.id = 0;
        tL_message.date = (int) (System.currentTimeMillis() / 1000);
        tL_message.message = fileName;
        tL_message.out = true;
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = clientUserId;
        tL_message.peer_id = peer;
        TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
        fromPeer.user_id = clientUserId;
        tL_message.from_id = fromPeer;
        TLRPC.TL_messageMediaWebPage mediaWebPage = new TLRPC.TL_messageMediaWebPage();
        mediaWebPage.webpage = tL_webPage;
        tL_message.media = mediaWebPage;
        return new MessageObject(UserConfig.selectedAccount, tL_message, false, true);
    }

    private String normalizeFileName(File file, String fileName) {
        if (fileName == null || TextUtils.isEmpty(fileName)) {
            fileName = file.getName();
        }
        String lowerCase = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lowerCase.endsWith(".plugin")) {
            return fileName;
        }
        return fileName + ".plugin";
    }
}