/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;

@Dao
public interface SpyDao {
    @Insert
    void insert(SpyMessageRead read);

    @Insert
    void insert(SpyMessageContentsRead contentsRead);

    @Query("SELECT * FROM SpyMessageRead WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId LIMIT 1")
    SpyMessageRead getMessageRead(long userId, long dialogId, int messageId);

    @Query("SELECT * FROM SpyMessageContentsRead WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId LIMIT 1")
    SpyMessageContentsRead getMessageContentsRead(long userId, long dialogId, int messageId);

    @Query("DELETE FROM SpyMessageRead WHERE entityCreateDate < :cutoff")
    void deleteOldReads(int cutoff);

    @Query("DELETE FROM SpyMessageContentsRead WHERE entityCreateDate < :cutoff")
    void deleteOldContentsRead(int cutoff);

    @Query("DELETE FROM SpyMessageRead WHERE userId = :userId AND dialogId = :dialogId")
    void deleteForDialog(long userId, long dialogId);

    @Query("DELETE FROM SpyMessageContentsRead WHERE userId = :userId AND dialogId = :dialogId")
    void deleteContentsForDialog(long userId, long dialogId);

    @Query("DELETE FROM SpyMessageRead")
    void deleteAll();

    @Query("DELETE FROM SpyMessageContentsRead")
    void deleteAllContents();
}
