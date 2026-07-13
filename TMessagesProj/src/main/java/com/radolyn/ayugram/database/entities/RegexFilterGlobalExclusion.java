/*
 * Room-backed per-dialog exclusion of a shared regex filter.
 *
 * Mirrors AyuFilter.ExcludedFilterEntry: a (dialogId, filterId) pair meaning
 * "do not apply the shared filter identified by filterId in this dialog".
 * filterId references RegexFilter.id (a UUID string).
 */

package com.radolyn.ayugram.database.entities;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "RegexFilterGlobalExclusion",
        primaryKeys = {"dialogId", "filterId"},
        indices = {
                @Index(value = "dialogId"),
                @Index(value = "filterId")
        }
)
public class RegexFilterGlobalExclusion {
    public long dialogId;
    @NonNull
    public String filterId;
}
