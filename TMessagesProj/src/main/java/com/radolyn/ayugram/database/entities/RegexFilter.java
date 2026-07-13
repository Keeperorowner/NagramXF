
package com.radolyn.ayugram.database.entities;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "RegexFilter",
        indices = {
                @Index(value = "dialogId"),
                @Index(value = "enabled")
        }
)
public class RegexFilter {
    @PrimaryKey
    @NonNull
    public String id;
    public String text;
    public Long dialogId;
    public boolean enabled;
    public boolean caseInsensitive;
    public boolean reversed;
}
