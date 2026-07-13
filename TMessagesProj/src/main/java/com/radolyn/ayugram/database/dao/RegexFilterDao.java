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
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import java.util.List;

@Dao
public interface RegexFilterDao {

    // --- Filters ---

    @Query("SELECT * FROM RegexFilter ORDER BY rowid DESC")
    List<RegexFilter> getAll();

    @Query("SELECT * FROM RegexFilter WHERE dialogId IS NULL ORDER BY rowid DESC")
    List<RegexFilter> getShared();

    @Query("SELECT * FROM RegexFilter WHERE dialogId = :dialogId ORDER BY rowid DESC")
    List<RegexFilter> getByDialogId(long dialogId);

    @Query("SELECT * FROM RegexFilter WHERE id = :id LIMIT 1")
    RegexFilter getById(String id);

    @Query("SELECT COUNT(*) FROM RegexFilter")
    int getCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(RegexFilter filter);

    @Query("DELETE FROM RegexFilter WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM RegexFilter")
    void deleteAllFilters();

    @Query("DELETE FROM RegexFilter WHERE dialogId IS NULL")
    void deleteAllShared();

    @Query("DELETE FROM RegexFilter WHERE dialogId = :dialogId")
    void deleteByDialogId(long dialogId);

    // --- Exclusions ---

    @Query("SELECT * FROM RegexFilterGlobalExclusion")
    List<RegexFilterGlobalExclusion> getAllExclusions();

    @Query("SELECT * FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId")
    List<RegexFilterGlobalExclusion> getExclusionsByDialogId(long dialogId);

    @Query("SELECT EXISTS(SELECT 1 FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId AND filterId = :filterId)")
    boolean isExcluded(long dialogId, String filterId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertExclusion(RegexFilterGlobalExclusion exclusion);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId AND filterId = :filterId")
    void deleteExclusion(long dialogId, String filterId);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE filterId = :filterId")
    void deleteExclusionsByFilterId(String filterId);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId")
    void deleteExclusionsByDialogId(long dialogId);

    @Query("DELETE FROM RegexFilterGlobalExclusion")
    void deleteAllExclusions();
}
