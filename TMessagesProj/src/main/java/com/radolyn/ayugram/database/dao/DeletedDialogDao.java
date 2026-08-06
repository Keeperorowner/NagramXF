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
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import com.radolyn.ayugram.database.entities.DeletedDialog;

import java.util.List;

@Dao
public interface DeletedDialogDao {
    @Insert
    long insert(DeletedDialog deletedDialog);

    @Delete
    void delete(DeletedDialog deletedDialog);

    @Query("SELECT * FROM DeletedDialog WHERE userId = :userId ORDER BY entityCreateDate DESC")
    List<DeletedDialog> getAll(long userId);

    @Query("SELECT * FROM DeletedDialog WHERE userId = :userId AND dialogId = :dialogId")
    DeletedDialog get(long userId, long dialogId);

    @Query("SELECT COUNT(*) FROM DeletedDialog")
    int getDeletedCount();

    @Query("DELETE FROM DeletedDialog WHERE userId = :userId AND dialogId = :dialogId")
    void delete(long userId, long dialogId);

    @Query("DELETE FROM DeletedDialog WHERE userId = :userId AND dialogId IN (:dialogIds)")
    void deleteExisting(long userId, List<Long> dialogIds);

    @Query("DELETE FROM DeletedDialog")
    void deleteAll();

    @Query("UPDATE DeletedDialog SET folderId = :folderId WHERE userId = :userId AND dialogId IN (:dialogIds)")
    void updateDialogsFolder(long userId, List<Long> dialogIds, int folderId);
}
