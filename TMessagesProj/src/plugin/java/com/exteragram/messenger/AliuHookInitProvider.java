package com.exteragram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

/**
 * Loads libaliuhook before Application.onCreate so hooks are ready before
 * plugin code runs. Skipped in safe mode.
 */
public class AliuHookInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        try {
            if (de.robv.android.xposed.XposedBridge.isNativeAvailable()) {
                de.robv.android.xposed.XposedBridge.ensureInitialized();
            }
        } catch (Throwable t) {
            FileLog.e("AliuHookInitProvider: early aliuhook init failed", t);
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
