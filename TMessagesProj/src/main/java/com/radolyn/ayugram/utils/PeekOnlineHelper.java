package com.radolyn.ayugram.utils;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

public class PeekOnlineHelper {

    public interface Callback {
        void onSuccess(TLRPC.User user, String formattedStatus, int sourceAccount);

        void onError(TLRPC.TL_error error);

        void onExactStatusUnavailable();

        void onRestoreFailed(TLRPC.TL_error error, int account);
    }

    public static void peekOnline(int currentAccount, TLRPC.User user, Callback callback) {
        if (callback == null) {
            return;
        }
        if (user == null || user.bot || UserObject.isDeleted(user) || UserObject.isUserSelf(user)) {
            callback.onError(null);
            return;
        }

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.InputUser targetInputUser = messagesController.getInputUser(user);
        if (targetInputUser instanceof TLRPC.TL_inputUserEmpty) {
            callback.onError(null);
            return;
        }

        attemptPeek(collectProbeAccounts(currentAccount), 0, user, null, callback);
    }

    private static void attemptPeek(ArrayList<Integer> probeAccounts, int index, TLRPC.User originalUser, TLRPC.TL_error lastError, Callback callback) {
        if (index >= probeAccounts.size()) {
            if (lastError != null) {
                callback.onError(lastError);
            } else {
                callback.onExactStatusUnavailable();
            }
            return;
        }

        int probeAccount = probeAccounts.get(index);
        resolveUserForAccount(probeAccount, originalUser, resolvedUser -> {
            if (resolvedUser == null) {
                attemptPeek(probeAccounts, index + 1, originalUser, lastError, callback);
                return;
            }

            peekOnlineSingleAccount(probeAccount, resolvedUser, new SingleAccountCallback() {
                @Override
                public void onSuccess(TLRPC.User user) {
                    callback.onSuccess(user, LocaleController.formatUserStatus(probeAccount, user), probeAccount);
                }

                @Override
                public void onContinue(TLRPC.TL_error error) {
                    attemptPeek(probeAccounts, index + 1, originalUser, error != null ? error : lastError, callback);
                }

                @Override
                public void onRestoreFailed(TLRPC.TL_error error) {
                    callback.onRestoreFailed(error, probeAccount);
                }
            });
        });
    }

    private static ArrayList<Integer> collectProbeAccounts(int currentAccount) {
        ArrayList<Integer> accounts = new ArrayList<>();
        if (UserConfig.getInstance(currentAccount).isClientActivated()) {
            accounts.add(currentAccount);
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (account == currentAccount || !UserConfig.getInstance(account).isClientActivated() || PasscodeHelper.isAccountHidden(account)) {
                continue;
            }
            accounts.add(account);
        }
        return accounts;
    }

    private static void resolveUserForAccount(int account, TLRPC.User originalUser, ResolvedUserCallback callback) {
        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.User resolvedUser = messagesController.getUser(originalUser.id);
        if (resolvedUser == null) {
            resolvedUser = MessagesStorage.getInstance(account).getUserSync(originalUser.id);
            if (resolvedUser != null) {
                messagesController.putUser(resolvedUser, true, true);
            }
        }
        if (resolvedUser != null) {
            callback.onResolved(resolvedUser);
            return;
        }
        if (TextUtils.isEmpty(originalUser.username)) {
            callback.onResolved(null);
            return;
        }
        messagesController.getUserNameResolver().resolve(originalUser.username, peerId -> {
            if (peerId == null || peerId <= 0 || peerId != originalUser.id) {
                callback.onResolved(null);
                return;
            }
            TLRPC.User user = messagesController.getUser(peerId);
            if (user == null) {
                user = MessagesStorage.getInstance(account).getUserSync(peerId);
                if (user != null) {
                    messagesController.putUser(user, true, true);
                }
            }
            callback.onResolved(user);
        });
    }

    private static void peekOnlineSingleAccount(int currentAccount, TLRPC.User user, SingleAccountCallback callback) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.InputUser targetInputUser = messagesController.getInputUser(user);
        if (targetInputUser instanceof TLRPC.TL_inputUserEmpty) {
            callback.onContinue(null);
            return;
        }

        TL_account.getPrivacy request = new TL_account.getPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                callback.onContinue(error);
                return;
            }
            if (!(response instanceof TL_account.privacyRules)) {
                callback.onContinue(null);
                return;
            }

            TL_account.privacyRules privacyRules = (TL_account.privacyRules) response;
            messagesController.putUsers(privacyRules.users, false);
            messagesController.putChats(privacyRules.chats, false);
            ContactsController.getInstance(currentAccount).setPrivacyRules(privacyRules.rules, ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);

            ArrayList<TLRPC.PrivacyRule> originalRules = privacyRules.rules != null ? privacyRules.rules : new ArrayList<>();
            ArrayList<TLRPC.InputPrivacyRule> originalInputRules = buildInputRules(messagesController, originalRules, targetInputUser, user.id, false);
            ArrayList<TLRPC.InputPrivacyRule> temporaryInputRules = buildInputRules(messagesController, originalRules, targetInputUser, user.id, true);
            if (originalInputRules == null || temporaryInputRules == null) {
                callback.onContinue(null);
                return;
            }

            applyPrivacyRules(currentAccount, temporaryInputRules, (temporaryResponse, temporaryError) -> {
                if (temporaryError != null || temporaryResponse == null) {
                    callback.onContinue(temporaryError);
                    return;
                }
                messagesController.putUsers(temporaryResponse.users, false);
                messagesController.putChats(temporaryResponse.chats, false);
                ContactsController.getInstance(currentAccount).setPrivacyRules(temporaryResponse.rules, ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);

                fetchUserStatus(currentAccount, user, targetInputUser, (fetchedUser, fetchError) ->
                    restorePrivacyRules(currentAccount, originalInputRules, () -> {
                        if (fetchError != null) {
                            callback.onContinue(fetchError);
                        } else if (isExactStatusAvailable(fetchedUser)) {
                            callback.onSuccess(fetchedUser);
                        } else {
                            callback.onContinue(null);
                        }
                    }, callback)
                );
            });
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void restorePrivacyRules(int currentAccount, ArrayList<TLRPC.InputPrivacyRule> originalInputRules, Runnable onRestoreSuccess, SingleAccountCallback callback) {
        applyPrivacyRules(currentAccount, originalInputRules, (restoreResponse, restoreError) -> {
            if (restoreError != null || restoreResponse == null) {
                FileLog.e("PeekOnline: failed to restore privacy rules" + (restoreError != null ? ": " + restoreError.text : ""));
                callback.onRestoreFailed(restoreError);
                return;
            }
            MessagesController.getInstance(currentAccount).putUsers(restoreResponse.users, false);
            MessagesController.getInstance(currentAccount).putChats(restoreResponse.chats, false);
            ContactsController.getInstance(currentAccount).setPrivacyRules(restoreResponse.rules, ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);
            if (onRestoreSuccess != null) {
                onRestoreSuccess.run();
            }
        });
    }

    private static void fetchUserStatus(int currentAccount, TLRPC.User user, TLRPC.InputUser targetInputUser, UserStatusCallback callback) {
        TLRPC.TL_users_getUsers request = new TLRPC.TL_users_getUsers();
        request.id.add(targetInputUser);
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                callback.onComplete(null, error);
                return;
            }

            TLRPC.User fetchedUser = extractUser(response, user.id);
            if (fetchedUser != null) {
                ArrayList<TLRPC.User> users = new ArrayList<>(1);
                users.add(fetchedUser);
                MessagesController.getInstance(currentAccount).putUsers(users, false);
                if (fetchedUser.status instanceof TLRPC.TL_userStatusOffline && fetchedUser.status.expires > 0) {
                    LastSeenHelper.saveLastSeen(fetchedUser.id, fetchedUser.status.expires);
                }
            }
            callback.onComplete(fetchedUser, null);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static TLRPC.User extractUser(TLObject response, long userId) {
        if (!(response instanceof Vector)) {
            return null;
        }
        ArrayList<Object> objects = ((Vector) response).objects;
        for (int i = 0; i < objects.size(); i++) {
            Object object = objects.get(i);
            if (object instanceof TLRPC.User) {
                TLRPC.User fetchedUser = (TLRPC.User) object;
                if (fetchedUser.id == userId) {
                    return fetchedUser;
                }
            }
        }
        return null;
    }

    private static boolean isExactStatusAvailable(TLRPC.User user) {
        if (user == null || user.status == null || user.status.expires == 0 || UserObject.isDeleted(user) || user instanceof TLRPC.TL_userEmpty) {
            return false;
        }
        int expires = user.status.expires;
        return expires != -1 && expires != -100 && expires != -101 && expires != -102;
    }

    private static ArrayList<TLRPC.InputPrivacyRule> buildInputRules(MessagesController messagesController, ArrayList<TLRPC.PrivacyRule> privacyRules, TLRPC.InputUser targetInputUser, long targetUserId, boolean injectTarget) {
        ArrayList<TLRPC.InputPrivacyRule> inputRules = new ArrayList<>();
        TLRPC.TL_inputPrivacyValueAllowUsers allowUsersRule = null;
        boolean targetAlreadyAllowed = false;

        for (int i = 0; i < privacyRules.size(); i++) {
            TLRPC.PrivacyRule privacyRule = privacyRules.get(i);
            if (privacyRule instanceof TLRPC.TL_privacyValueAllowUsers) {
                TLRPC.TL_inputPrivacyValueAllowUsers inputRule = new TLRPC.TL_inputPrivacyValueAllowUsers();
                ArrayList<Long> users = ((TLRPC.TL_privacyValueAllowUsers) privacyRule).users;
                for (int j = 0; j < users.size(); j++) {
                    long userId = users.get(j);
                    if (!addResolvedUser(inputRule.users, messagesController, userId)) {
                        FileLog.e("PeekOnline: failed to resolve allow user " + userId);
                        return null;
                    }
                    if (injectTarget && userId == targetUserId) {
                        targetAlreadyAllowed = true;
                    }
                }
                if (!inputRule.users.isEmpty()) {
                    if (allowUsersRule == null) {
                        allowUsersRule = inputRule;
                    }
                    inputRules.add(inputRule);
                }
            } else if (privacyRule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                TLRPC.TL_inputPrivacyValueDisallowUsers inputRule = new TLRPC.TL_inputPrivacyValueDisallowUsers();
                ArrayList<Long> users = ((TLRPC.TL_privacyValueDisallowUsers) privacyRule).users;
                for (int j = 0; j < users.size(); j++) {
                    long userId = users.get(j);
                    if (injectTarget && userId == targetUserId) {
                        continue;
                    }
                    if (!addResolvedUser(inputRule.users, messagesController, userId)) {
                        FileLog.e("PeekOnline: failed to resolve disallow user " + userId);
                        return null;
                    }
                }
                if (!inputRule.users.isEmpty()) {
                    inputRules.add(inputRule);
                }
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                TLRPC.TL_inputPrivacyValueAllowChatParticipants inputRule = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                inputRule.chats.addAll(((TLRPC.TL_privacyValueAllowChatParticipants) privacyRule).chats);
                if (!inputRule.chats.isEmpty()) {
                    inputRules.add(inputRule);
                }
            } else if (privacyRule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                TLRPC.TL_inputPrivacyValueDisallowChatParticipants inputRule = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                inputRule.chats.addAll(((TLRPC.TL_privacyValueDisallowChatParticipants) privacyRule).chats);
                if (!inputRule.chats.isEmpty()) {
                    inputRules.add(inputRule);
                }
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowAll) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueDisallowAll) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowContacts) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueDisallowContacts) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowCloseFriends) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowPremium) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueAllowBots) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueAllowBots());
            } else if (privacyRule instanceof TLRPC.TL_privacyValueDisallowBots) {
                inputRules.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
            } else {
                FileLog.e("PeekOnline: unsupported privacy rule " + privacyRule.getClass().getName());
                return null;
            }
        }

        if (injectTarget && !targetAlreadyAllowed) {
            if (allowUsersRule == null) {
                allowUsersRule = new TLRPC.TL_inputPrivacyValueAllowUsers();
                inputRules.add(findAllowUsersInsertionIndex(inputRules), allowUsersRule);
            }
            allowUsersRule.users.add(targetInputUser);
        }

        return inputRules;
    }

    private static int findAllowUsersInsertionIndex(ArrayList<TLRPC.InputPrivacyRule> inputRules) {
        for (int i = 0; i < inputRules.size(); i++) {
            if (isBaseRule(inputRules.get(i))) {
                return i;
            }
        }
        return inputRules.size();
    }

    private static boolean isBaseRule(TLRPC.InputPrivacyRule rule) {
        return rule instanceof TLRPC.TL_inputPrivacyValueAllowAll
            || rule instanceof TLRPC.TL_inputPrivacyValueDisallowAll
            || rule instanceof TLRPC.TL_inputPrivacyValueAllowContacts
            || rule instanceof TLRPC.TL_inputPrivacyValueDisallowContacts
            || rule instanceof TLRPC.TL_inputPrivacyValueAllowCloseFriends
            || rule instanceof TLRPC.TL_inputPrivacyValueAllowPremium
            || rule instanceof TLRPC.TL_inputPrivacyValueAllowBots
            || rule instanceof TLRPC.TL_inputPrivacyValueDisallowBots;
    }

    private static boolean addResolvedUser(ArrayList<TLRPC.InputUser> users, MessagesController messagesController, long userId) {
        TLRPC.InputUser inputUser = messagesController.getInputUser(userId);
        if (inputUser instanceof TLRPC.TL_inputUserEmpty) {
            return false;
        }
        users.add(inputUser);
        return true;
    }

    private static void applyPrivacyRules(int currentAccount, ArrayList<TLRPC.InputPrivacyRule> rules, PrivacyRulesCallback callback) {
        TL_account.setPrivacy request = new TL_account.setPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        request.rules.addAll(rules);
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                callback.onComplete(null, error);
            } else if (response instanceof TL_account.privacyRules) {
                callback.onComplete((TL_account.privacyRules) response, null);
            } else {
                callback.onComplete(null, null);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private interface PrivacyRulesCallback {
        void onComplete(TL_account.privacyRules privacyRules, TLRPC.TL_error error);
    }

    private interface UserStatusCallback {
        void onComplete(TLRPC.User user, TLRPC.TL_error error);
    }

    private interface ResolvedUserCallback {
        void onResolved(TLRPC.User user);
    }

    private interface SingleAccountCallback {
        void onSuccess(TLRPC.User user);

        void onContinue(TLRPC.TL_error error);

        void onRestoreFailed(TLRPC.TL_error error);
    }
}
