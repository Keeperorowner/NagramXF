package com.radolyn.ayugram.database.entities;

import androidx.room.Entity;

@Entity(primaryKeys = {"userId"})
public class SpyLastSeen {
    public long userId;
    public int lastSeenDate;
}
