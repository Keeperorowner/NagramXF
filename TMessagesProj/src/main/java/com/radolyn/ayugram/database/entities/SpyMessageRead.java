
package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        indices = {
                @Index(value = {"userId", "dialogId", "messageId"}),
                @Index(value = {"userId", "entityCreateDate"})
        }
)
public class SpyMessageRead {
    @PrimaryKey(autoGenerate = true)
    public long fakeId;
    public long userId;
    public long dialogId;
    public int messageId;
    public int entityCreateDate;
}
