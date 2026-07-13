
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
