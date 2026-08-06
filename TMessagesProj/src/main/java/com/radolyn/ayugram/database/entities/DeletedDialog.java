/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        indices = {
                @Index(value = {"userId", "dialogId"}),
                @Index(value = {"userId", "entityCreateDate"})
        }
)
public class DeletedDialog {
    @PrimaryKey(autoGenerate = true)
    public long fakeId;

    public long userId;
    public long dialogId;
    public long peerId;
    public Integer folderId;
    public int topMessage;
    public int lastMessageDate;
    public int flags;
    public int entityCreateDate;
}
