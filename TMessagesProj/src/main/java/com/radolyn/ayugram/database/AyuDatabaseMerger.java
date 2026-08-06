package com.radolyn.ayugram.database;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.FileLog;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把外部 ayu-data 数据库合并进当前库。
 *
 * 按列名交集迁移，不依赖 Room 的 schema 校验，因此可以吃下列结构不同的库
 * （AyuGram 与本项目的实体字段已分叉）。合并删除/编辑消息、消息反应及已删除对话，
 * 重复消息按 (userId, dialogId, messageId, entityCreateDate) 跳过。
 */
public class AyuDatabaseMerger {

    private static final String SOURCE = "src";
    private static final String DELETED_MESSAGE = "DeletedMessage";
    private static final String EDITED_MESSAGE = "EditedMessage";
    private static final String DELETED_MESSAGE_REACTION = "DeletedMessageReaction";
    private static final String DELETED_DIALOG = "DeletedDialog";
    private static final String DELETED_CANDIDATES = "temp_deleted_candidates";
    private static final String DELETED_MAP = "temp_deleted_map";
    private static final String EDITED_CANDIDATES = "temp_edited_candidates";
    private static final String DIALOG_CANDIDATES = "temp_dialog_candidates";

    public interface ProgressCallback {
        void onProgress(int done, int total);
    }

    /**
     * @return 合并进来的删除消息和编辑历史条数
     */
    public static int merge(File sourceFile, ProgressCallback callback) throws Exception {
        if (sourceFile == null || !sourceFile.exists()) {
            return 0;
        }
        return AyuData.withClosedDatabase(() -> mergeInto(AyuData.getDatabaseFileInternal(), sourceFile, callback));
    }

    /**
     * 判断该文件能否整包替换本地库：要求版本号不高于当前，且列结构是本项目自己导出的。
     * 外部（如 AyuGram）导出的库结构不同，整包替换会被 Room 校验拒绝，只能走合并。
     */
    public static boolean canReplaceWith(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0L) {
            return false;
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(sourceFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (database.getVersion() > AyuDatabase.VERSION) {
                return false;
            }
            List<String> columns = tableColumns(database, "main", DELETED_MESSAGE);
            if (columns.isEmpty()) {
                return false;
            }
            // 本项目独有的列：缺任何一个就说明不是我们导出的
            return columns.contains("forwards")
                    && columns.contains("replyQuote")
                    && columns.contains("replyQuoteText")
                    && columns.contains("replyFromSerialized");
        } catch (Exception e) {
            FileLog.e("canReplaceWith", e);
            return false;
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    /**
     * 该文件是否含可合并的消息表。
     */
    public static boolean hasMergeableData(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0L) {
            return false;
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(sourceFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            return !tableColumns(database, "main", DELETED_MESSAGE).isEmpty()
                    || !tableColumns(database, "main", EDITED_MESSAGE).isEmpty()
                    || !tableColumns(database, "main", DELETED_DIALOG).isEmpty();
        } catch (Exception e) {
            FileLog.e("hasMergeableData", e);
            return false;
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private static int mergeInto(File targetFile, File sourceFile, ProgressCallback callback) {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(targetFile.getAbsolutePath(), null, 0);
        try {
            database.execSQL("ATTACH DATABASE " + DatabaseUtils.sqlEscapeString(sourceFile.getAbsolutePath()) + " AS " + SOURCE);
            try {
                return mergeAttached(database, callback);
            } finally {
                try {
                    database.execSQL("DETACH DATABASE " + SOURCE);
                } catch (Exception e) {
                    FileLog.e("AyuDatabaseMerger detach", e);
                }
            }
        } finally {
            database.close();
        }
    }

    private static int mergeAttached(SQLiteDatabase database, ProgressCallback callback) {
        int deletedCount = hasTable(database, DELETED_MESSAGE) ? count(database, SOURCE, DELETED_MESSAGE) : 0;
        int editedCount = hasTable(database, EDITED_MESSAGE) ? count(database, SOURCE, EDITED_MESSAGE) : 0;
        int dialogCount = hasTable(database, DELETED_DIALOG) ? count(database, SOURCE, DELETED_DIALOG) : 0;
        int total = deletedCount + editedCount;
        emitProgress(callback, 0, total);
        if (total == 0 && dialogCount == 0) {
            return 0;
        }

        int merged = 0;
        database.beginTransaction();
        try {
            if (hasTable(database, DELETED_DIALOG)) {
                mergeDeletedDialogs(database);
            }
            if (deletedCount > 0) {
                merged += mergeDeletedMessages(database);
                emitProgress(callback, deletedCount, total);
            }
            if (editedCount > 0) {
                merged += mergeEditedMessages(database);
                emitProgress(callback, total, total);
            }
            dropTempTables(database);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        importAttachments(database);
        return merged;
    }

    private static void importAttachments(SQLiteDatabase database) {
        importAttachmentsFromTable(database, DELETED_MESSAGE);
        importAttachmentsFromTable(database, EDITED_MESSAGE);
    }

    private static void importAttachmentsFromTable(SQLiteDatabase database, String table) {
        if (!hasTable(database, table)) {
            return;
        }
        Cursor cursor = database.rawQuery(
                "SELECT userId, dialogId, messageId, entityCreateDate, mediaPath, hqThumbPath FROM " + SOURCE + "." + table
                        + " WHERE (mediaPath IS NOT NULL AND mediaPath <> '') OR (hqThumbPath IS NOT NULL AND hqThumbPath <> '')",
                null);
        try {
            int userIdIndex = cursor.getColumnIndex("userId");
            int dialogIdIndex = cursor.getColumnIndex("dialogId");
            int messageIdIndex = cursor.getColumnIndex("messageId");
            int createDateIndex = cursor.getColumnIndex("entityCreateDate");
            int mediaPathIndex = cursor.getColumnIndex("mediaPath");
            int thumbPathIndex = cursor.getColumnIndex("hqThumbPath");
            while (cursor.moveToNext()) {
                long userId = cursor.getLong(userIdIndex);
                long dialogId = cursor.getLong(dialogIdIndex);
                int messageId = cursor.getInt(messageIdIndex);
                int entityCreateDate = cursor.getInt(createDateIndex);
                String mediaPath = copyAttachment(cursor.getString(mediaPathIndex));
                String thumbPath = copyAttachment(cursor.getString(thumbPathIndex));
                if (mediaPath == null && thumbPath == null) {
                    continue;
                }

                String update = "UPDATE main." + table + " SET mediaPath = COALESCE(?, mediaPath), hqThumbPath = COALESCE(?, hqThumbPath)"
                        + " WHERE userId = ? AND dialogId = ? AND messageId = ? AND entityCreateDate = ?";
                database.execSQL(update, new Object[]{mediaPath, thumbPath, userId, dialogId, messageId, entityCreateDate});
            }
        } finally {
            cursor.close();
        }
    }

    private static String copyAttachment(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return null;
        }
        File source = new File(sourcePath);
        if (!source.isFile() || source.length() == 0L) {
            return null;
        }
        File targetDir = AyuMessagesController.attachmentsPath;
        if (targetDir == null) {
            return null;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return null;
        }
        File target = new File(targetDir, source.getName());
        try {
            if (!target.exists() || target.length() != source.length()) {
                copyFile(source, target);
            }
            return target.isFile() && target.length() > 0L ? target.getAbsolutePath() : null;
        } catch (IOException e) {
            FileLog.e("Failed to import Ayu attachment " + sourcePath, e);
            return null;
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static int mergeDeletedDialogs(SQLiteDatabase database) {
        List<String> columns = sharedColumns(database, DELETED_DIALOG, "fakeId");
        if (columns.isEmpty()) {
            return 0;
        }

        database.execSQL("DROP TABLE IF EXISTS " + DIALOG_CANDIDATES);
        database.execSQL("CREATE TEMP TABLE " + DIALOG_CANDIDATES + " AS SELECT "
                + prefixedColumnList("s", columns)
                + " FROM " + SOURCE + "." + DELETED_DIALOG + " s"
                + " WHERE NOT EXISTS (SELECT 1 FROM main." + DELETED_DIALOG + " t"
                + " WHERE t.`userId` = s.`userId` AND t.`dialogId` = s.`dialogId`)");

        int inserted = count(database, null, DIALOG_CANDIDATES);
        if (inserted > 0) {
            insertFromTemp(database, DELETED_DIALOG, DIALOG_CANDIDATES, columns);
        }
        return inserted;
    }

    private static int mergeDeletedMessages(SQLiteDatabase database) {
        List<String> columns = sharedColumns(database, DELETED_MESSAGE, "fakeId");
        if (columns.isEmpty()) {
            return 0;
        }

        // 先挑出目标库里不存在的记录，并保留来源 fakeId 以便重建反应外键
        database.execSQL("DROP TABLE IF EXISTS " + DELETED_CANDIDATES);
        database.execSQL("CREATE TEMP TABLE " + DELETED_CANDIDATES + " AS SELECT s.`fakeId` AS srcFakeId, "
                + prefixedColumnList("s", columns)
                + " FROM " + SOURCE + "." + DELETED_MESSAGE + " s"
                + " WHERE NOT EXISTS (SELECT 1 FROM main." + DELETED_MESSAGE + " t"
                + " WHERE t.`userId` = s.`userId` AND t.`dialogId` = s.`dialogId`"
                + " AND t.`messageId` = s.`messageId` AND t.`entityCreateDate` = s.`entityCreateDate`)");

        int inserted = count(database, null, DELETED_CANDIDATES);
        if (inserted == 0) {
            return 0;
        }

        insertFromTemp(database, DELETED_MESSAGE, DELETED_CANDIDATES, columns);
        createDeletedIdMap(database, columns);
        mergeDeletedMessageReactions(database);
        return inserted;
    }

    /**
     * 建立 来源 fakeId → 新插入的 fakeId 映射，供反应表重定向外键。
     */
    private static void createDeletedIdMap(SQLiteDatabase database, List<String> columns) {
        database.execSQL("DROP TABLE IF EXISTS " + DELETED_MAP);
        database.execSQL("CREATE TEMP TABLE " + DELETED_MAP + " AS SELECT c.srcFakeId AS srcFakeId, t.`fakeId` AS newFakeId"
                + " FROM " + DELETED_CANDIDATES + " c JOIN main." + DELETED_MESSAGE + " t"
                + " ON t.`userId` = c.`userId` AND t.`dialogId` = c.`dialogId`"
                + " AND t.`messageId` = c.`messageId` AND t.`entityCreateDate` = c.`entityCreateDate`");
    }

    private static void mergeDeletedMessageReactions(SQLiteDatabase database) {
        if (!hasTable(database, DELETED_MESSAGE_REACTION)) {
            return;
        }
        List<String> columns = sharedColumns(database, DELETED_MESSAGE_REACTION, "fakeReactionId");
        if (columns.isEmpty() || !columns.contains("deletedMessageId")) {
            return;
        }
        database.execSQL("INSERT INTO main." + DELETED_MESSAGE_REACTION + " (" + columnList(columns) + reactionExtraTargetColumns(database, columns) + ")"
                + " SELECT " + reactionSelectList(columns) + reactionExtraSelectValues(database, columns)
                + " FROM " + SOURCE + "." + DELETED_MESSAGE_REACTION + " r"
                + " JOIN " + DELETED_MAP + " m ON m.srcFakeId = r.`deletedMessageId`");
    }

    private static String reactionExtraTargetColumns(SQLiteDatabase database, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : requiredMissingColumns(database, DELETED_MESSAGE_REACTION, columns)) {
            sb.append(", `").append(column).append('`');
        }
        return sb.toString();
    }

    private static String reactionExtraSelectValues(SQLiteDatabase database, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : requiredMissingColumns(database, DELETED_MESSAGE_REACTION, columns)) {
            sb.append(", ").append(defaultLiteralFor(database, DELETED_MESSAGE_REACTION, column));
        }
        return sb.toString();
    }

    private static int mergeEditedMessages(SQLiteDatabase database) {
        List<String> columns = sharedColumns(database, EDITED_MESSAGE, "fakeId");
        if (columns.isEmpty()) {
            return 0;
        }

        database.execSQL("DROP TABLE IF EXISTS " + EDITED_CANDIDATES);
        database.execSQL("CREATE TEMP TABLE " + EDITED_CANDIDATES + " AS SELECT "
                + prefixedColumnList("s", columns)
                + " FROM " + SOURCE + "." + EDITED_MESSAGE + " s"
                + " WHERE NOT EXISTS (SELECT 1 FROM main." + EDITED_MESSAGE + " t"
                + " WHERE t.`userId` = s.`userId` AND t.`dialogId` = s.`dialogId`"
                + " AND t.`messageId` = s.`messageId` AND t.`entityCreateDate` = s.`entityCreateDate`)");

        int inserted = count(database, null, EDITED_CANDIDATES);
        if (inserted == 0) {
            return 0;
        }

        insertFromTemp(database, EDITED_MESSAGE, EDITED_CANDIDATES, columns);
        return inserted;
    }

    private static void insertFromTemp(SQLiteDatabase database, String targetTable, String tempTable, List<String> columns) {
        String list = columnList(columns);
        // 目标库里 NOT NULL 且无默认值、但来源库没有的列必须显式补值，否则 INSERT 会失败
        List<String> missing = requiredMissingColumns(database, targetTable, columns);
        if (missing.isEmpty()) {
            database.execSQL("INSERT INTO main." + targetTable + " (" + list + ") SELECT " + list + " FROM " + tempTable);
            return;
        }
        StringBuilder targetCols = new StringBuilder(list);
        StringBuilder selectCols = new StringBuilder(list);
        for (String column : missing) {
            targetCols.append(", `").append(column).append('`');
            selectCols.append(", ").append(defaultLiteralFor(database, targetTable, column));
        }
        database.execSQL("INSERT INTO main." + targetTable + " (" + targetCols + ") SELECT " + selectCols + " FROM " + tempTable);
    }

    /**
     * 目标表中 NOT NULL、无默认值、非主键，且不在待迁移列里的列。
     */
    private static List<String> requiredMissingColumns(SQLiteDatabase database, String table, List<String> columns) {
        ArrayList<String> missing = new ArrayList<>();
        Cursor cursor = database.rawQuery("PRAGMA main.table_info(`" + table + "`)", null);
        try {
            int nameIndex = cursor.getColumnIndex("name");
            int notNullIndex = cursor.getColumnIndex("notnull");
            int defaultIndex = cursor.getColumnIndex("dflt_value");
            int pkIndex = cursor.getColumnIndex("pk");
            if (nameIndex < 0 || notNullIndex < 0 || defaultIndex < 0 || pkIndex < 0) {
                return missing;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                boolean notNull = cursor.getInt(notNullIndex) != 0;
                boolean hasDefault = !cursor.isNull(defaultIndex);
                boolean isPk = cursor.getInt(pkIndex) != 0;
                if (notNull && !hasDefault && !isPk && !columns.contains(name)) {
                    missing.add(name);
                }
            }
        } finally {
            cursor.close();
        }
        return missing;
    }

    private static String defaultLiteralFor(SQLiteDatabase database, String table, String column) {
        Cursor cursor = database.rawQuery("PRAGMA main.table_info(`" + table + "`)", null);
        try {
            int nameIndex = cursor.getColumnIndex("name");
            int typeIndex = cursor.getColumnIndex("type");
            if (nameIndex >= 0 && typeIndex >= 0) {
                while (cursor.moveToNext()) {
                    if (column.equals(cursor.getString(nameIndex))) {
                        String type = cursor.getString(typeIndex);
                        if (type != null) {
                            String upper = type.toUpperCase();
                            if (upper.contains("INT") || upper.contains("REAL")
                                    || upper.contains("NUM") || upper.contains("DOUB") || upper.contains("FLOA")) {
                                return "0";
                            }
                            if (upper.contains("BLOB")) {
                                return "x''";
                            }
                        }
                        return "''";
                    }
                }
            }
        } finally {
            cursor.close();
        }
        return "0";
    }

    private static void dropTempTables(SQLiteDatabase database) {
        database.execSQL("DROP TABLE IF EXISTS " + DELETED_CANDIDATES);
        database.execSQL("DROP TABLE IF EXISTS " + DELETED_MAP);
        database.execSQL("DROP TABLE IF EXISTS " + EDITED_CANDIDATES);
        database.execSQL("DROP TABLE IF EXISTS " + DIALOG_CANDIDATES);
    }

    private static void emitProgress(ProgressCallback callback, int done, int total) {
        if (callback != null) {
            callback.onProgress(done, total);
        }
    }

    private static boolean hasTable(SQLiteDatabase database, String table) {
        Cursor cursor = database.rawQuery(
                "SELECT 1 FROM " + SOURCE + ".sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{table});
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private static int count(SQLiteDatabase database, String schema, String table) {
        String target = schema == null ? table : schema + "." + table;
        Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM " + target, null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * 取来源表与目标表的列名交集，排除自增主键（由目标库重新分配）。
     */
    private static List<String> sharedColumns(SQLiteDatabase database, String table, String excluded) {
        List<String> sourceColumns = tableColumns(database, SOURCE, table);
        List<String> targetColumns = tableColumns(database, "main", table);
        ArrayList<String> shared = new ArrayList<>();
        for (String column : sourceColumns) {
            if (!column.equals(excluded) && targetColumns.contains(column)) {
                shared.add(column);
            }
        }
        return shared;
    }

    private static List<String> tableColumns(SQLiteDatabase database, String schema, String table) {
        ArrayList<String> columns = new ArrayList<>();
        // PRAGMA schema.table_info 在各 Android 版本上都可用，比表值函数形式更稳
        Cursor cursor = database.rawQuery("PRAGMA " + schema + ".table_info(`" + table + "`)", null);
        try {
            int nameIndex = cursor.getColumnIndex("name");
            if (nameIndex < 0) {
                return columns;
            }
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        } finally {
            cursor.close();
        }
        return columns;
    }

    private static String prefixedColumnList(String prefix, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(prefix).append(".`").append(column).append('`');
        }
        return sb.toString();
    }

    private static String columnList(List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('`').append(column).append('`');
        }
        return sb.toString();
    }

    /**
     * 反应表的 deletedMessageId 换成映射后的新 fakeId，其余列原样取。
     */
    private static String reactionSelectList(List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (column.equals("deletedMessageId")) {
                sb.append("m.newFakeId");
            } else {
                sb.append("r.`").append(column).append('`');
            }
        }
        return sb.toString();
    }
}
