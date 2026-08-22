/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.radolyn.ayugram.database.dao.DeletedDialogDao;
import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.dao.SpyDao;
import com.radolyn.ayugram.database.entities.DeletedDialog;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;
import com.radolyn.ayugram.database.entities.SpyLastSeen;
import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;

@Database(entities = {
        EditedMessage.class,
        DeletedMessage.class,
        DeletedMessageReaction.class,
        DeletedDialog.class,
        SpyLastSeen.class,
        SpyMessageRead.class,
        SpyMessageContentsRead.class,
        RegexFilter.class,
        RegexFilterGlobalExclusion.class
}, version = AyuDatabase.VERSION)
public abstract class AyuDatabase extends RoomDatabase {
    public static final int VERSION = 35;

    public abstract EditedMessageDao editedMessageDao();

    public abstract DeletedMessageDao deletedMessageDao();

    public abstract DeletedDialogDao deletedDialogDao();

    public abstract SpyDao spyDao();

    public abstract RegexFilterDao regexFilterDao();
}
