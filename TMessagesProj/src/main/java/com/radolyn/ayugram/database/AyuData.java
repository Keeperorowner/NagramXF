/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.dao.DeletedDialogDao;
import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.dao.SpyDao;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import tw.nekomimi.nekogram.utils.AndroidUtil;
import tw.nekomimi.nekogram.utils.FileUtil;

public class AyuData {
    public static long dbSize, attachmentsSize, totalSize;
    private static AyuDatabase database;
    private static EditedMessageDao editedMessageDao;
    private static DeletedMessageDao deletedMessageDao;
    private static DeletedDialogDao deletedDialogDao;
    private static SpyDao spyDao;
    private static RegexFilterDao regexFilterDao;
    private static final int IO_BUFFER_SIZE = 16 * 1024;

    private static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessage_userId_dialogId_messageId ON deletedmessage(userId, dialogId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessage_userId_dialogId_topicId_messageId ON deletedmessage(userId, dialogId, topicId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessage_userId_dialogId_replyMessageId_messageId ON deletedmessage(userId, dialogId, replyMessageId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessage_userId_dialogId_groupedId_messageId ON deletedmessage(userId, dialogId, groupedId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessage_dialogId ON deletedmessage(dialogId)");

            database.execSQL("CREATE INDEX IF NOT EXISTS index_editedmessage_userId_dialogId_messageId_entityCreateDate ON editedmessage(userId, dialogId, messageId, entityCreateDate)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_editedmessage_userId_entityCreateDate ON editedmessage(userId, entityCreateDate)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_editedmessage_dialogId_messageId ON editedmessage(dialogId, messageId)");

            database.execSQL("CREATE INDEX IF NOT EXISTS index_deletedmessagereaction_deletedMessageId ON deletedmessagereaction(deletedMessageId)");
        }
    };

    private static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN replyQuote INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN replyQuoteText TEXT");
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN replyQuoteEntities BLOB");
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN replyFromSerialized BLOB");

            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN replyQuote INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN replyQuoteText TEXT");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN replyQuoteEntities BLOB");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN replyFromSerialized BLOB");
        }
    };

    private static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN replyMarkupSerialized BLOB");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN replyMarkupSerialized BLOB");
        }
    };

    private static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN forwards INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN forwards INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS LastSeenEntity (userId INTEGER NOT NULL, lastSeen INTEGER NOT NULL, PRIMARY KEY(userId))");
        }
    };

    private static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS SpyMessageRead (" +
                    "fakeId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "userId INTEGER NOT NULL, " +
                    "dialogId INTEGER NOT NULL, " +
                    "messageId INTEGER NOT NULL, " +
                    "entityCreateDate INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_SpyMessageRead_userId_dialogId_messageId ON SpyMessageRead(userId, dialogId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_SpyMessageRead_userId_entityCreateDate ON SpyMessageRead(userId, entityCreateDate)");

            database.execSQL("CREATE TABLE IF NOT EXISTS SpyMessageContentsRead (" +
                    "fakeId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "userId INTEGER NOT NULL, " +
                    "dialogId INTEGER NOT NULL, " +
                    "messageId INTEGER NOT NULL, " +
                    "entityCreateDate INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_SpyMessageContentsRead_userId_dialogId_messageId ON SpyMessageContentsRead(userId, dialogId, messageId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_SpyMessageContentsRead_userId_entityCreateDate ON SpyMessageContentsRead(userId, entityCreateDate)");
        }
    };

    private static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS RegexFilter (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "text TEXT, " +
                    "dialogId INTEGER, " +
                    "enabled INTEGER NOT NULL, " +
                    "caseInsensitive INTEGER NOT NULL, " +
                    "reversed INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_RegexFilter_dialogId ON RegexFilter(dialogId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_RegexFilter_enabled ON RegexFilter(enabled)");

            database.execSQL("CREATE TABLE IF NOT EXISTS RegexFilterGlobalExclusion (" +
                    "dialogId INTEGER NOT NULL, " +
                    "filterId TEXT NOT NULL, " +
                    "PRIMARY KEY(dialogId, filterId))");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_RegexFilterGlobalExclusion_dialogId ON RegexFilterGlobalExclusion(dialogId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_RegexFilterGlobalExclusion_filterId ON RegexFilterGlobalExclusion(filterId)");
        }
    };

    private static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE DeletedMessage ADD COLUMN postAuthor TEXT");
            database.execSQL("ALTER TABLE EditedMessage ADD COLUMN postAuthor TEXT");
            database.execSQL("ALTER TABLE DeletedMessageReaction ADD COLUMN isPaid INTEGER NOT NULL DEFAULT 0");
        }
    };

    private static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS DeletedDialog");
            database.execSQL("DROP INDEX IF EXISTS index_DeletedDialog_userId_dialogId");
            database.execSQL("DROP INDEX IF EXISTS index_DeletedDialog_userId_entityCreateDate");
        }
    };

    private static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `DeletedDialog` (" +
                    "`fakeId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`userId` INTEGER NOT NULL, " +
                    "`dialogId` INTEGER NOT NULL, " +
                    "`peerId` INTEGER NOT NULL, " +
                    "`folderId` INTEGER, " +
                    "`topMessage` INTEGER NOT NULL, " +
                    "`lastMessageDate` INTEGER NOT NULL, " +
                    "`flags` INTEGER NOT NULL, " +
                    "`entityCreateDate` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_DeletedDialog_userId_dialogId` ON `DeletedDialog` (`userId`, `dialogId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_DeletedDialog_userId_entityCreateDate` ON `DeletedDialog` (`userId`, `entityCreateDate`)");
        }
    };

    private static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `SpyLastSeen` (" +
                    "`userId` INTEGER NOT NULL, " +
                    "`lastSeenDate` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`userId`))");
            try {
                database.execSQL("INSERT OR REPLACE INTO SpyLastSeen (userId, lastSeenDate) " +
                        "SELECT userId, lastSeen FROM LastSeenEntity");
            } catch (Exception ignored) {
            }
            database.execSQL("DROP TABLE IF EXISTS LastSeenEntity");
        }
    };

    static {
        create();
    }

    private static AyuDatabase buildDatabase(boolean allowDestructiveMigrationOnDowngrade) {
        if (allowDestructiveMigrationOnDowngrade) {
            return Room.databaseBuilder(ApplicationLoader.applicationContext, AyuDatabase.class, AyuConstants.AYU_DATABASE)
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .addMigrations(MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32)
                    .build();
        }
        return Room.databaseBuilder(ApplicationLoader.applicationContext, AyuDatabase.class, AyuConstants.AYU_DATABASE)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32)
                .build();
    }

    public static synchronized void create() {
        if (database != null) {
            return;
        }
        database = buildDatabase(true);

        editedMessageDao = database.editedMessageDao();
        deletedMessageDao = database.deletedMessageDao();
        deletedDialogDao = database.deletedDialogDao();
        spyDao = database.spyDao();
        regexFilterDao = database.regexFilterDao();
        AyuMessagesController.refreshAfterDatabaseChange();
        FilterPrefsMigrator.runIfNeeded();
    }

    public static AyuDatabase getDatabase() {
        return database;
    }

    public static EditedMessageDao getEditedMessageDao() {
        return editedMessageDao;
    }

    public static DeletedMessageDao getDeletedMessageDao() {
        return deletedMessageDao;
    }

    public static DeletedDialogDao getDeletedDialogDao() {
        return deletedDialogDao;
    }

    public static SpyDao getSpyDao() {
        return spyDao;
    }

    public static RegexFilterDao getRegexFilterDao() {
        return regexFilterDao;
    }

    private static File getDatabaseFile() {
        return ApplicationLoader.applicationContext.getDatabasePath(AyuConstants.AYU_DATABASE);
    }

    private static File getWalFile(File dbFile) {
        return new File(dbFile.getAbsolutePath() + "-wal");
    }

    private static File getShmFile(File dbFile) {
        return new File(dbFile.getAbsolutePath() + "-shm");
    }

    private static void closeDatabase() {
        if (database != null) {
            try {
                database.getOpenHelper().getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Exception e) {
                FileLog.e(e);
            }
            try {
                database.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        database = null;
        editedMessageDao = null;
        deletedMessageDao = null;
        deletedDialogDao = null;
        spyDao = null;
        regexFilterDao = null;
    }

    public static synchronized void clean() {
        closeDatabase();

        ApplicationLoader.applicationContext.deleteDatabase(AyuConstants.AYU_DATABASE);
    }

    public static synchronized void exportDatabase(OutputStream outputStream) throws IOException {
        if (outputStream == null) {
            throw new IOException("Database export stream is null");
        }

        closeDatabase();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(outputStream))) {
            File dbFile = getDatabaseFile();
            addFileToZip(zipOutputStream, dbFile);
            addFileToZip(zipOutputStream, getWalFile(dbFile));
            addFileToZip(zipOutputStream, getShmFile(dbFile));
        } finally {
            create();
        }
    }

    public static synchronized void importDatabase(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IOException("Database import stream is null");
        }

        File cacheDir = AndroidUtilities.getCacheDir();
        File importDir = new File(cacheDir, "ayu_database_import");
        File backupDir = new File(cacheDir, "ayu_database_backup");
        if (importDir.exists()) {
            FileUtil.deleteDirectory(importDir);
        }
        if (backupDir.exists()) {
            FileUtil.deleteDirectory(backupDir);
        }
        if (!importDir.exists() && !importDir.mkdirs()) {
            throw new IOException("Unable to create temporary import directory");
        }
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Unable to create temporary backup directory");
        }

        File dbFile = getDatabaseFile();
        File importDbFile = new File(importDir, dbFile.getName());
        File importWalFile = new File(importDir, dbFile.getName() + "-wal");
        File importShmFile = new File(importDir, dbFile.getName() + "-shm");

        try {
            BufferedInputStream bufferedInputStream = inputStream instanceof BufferedInputStream
                    ? (BufferedInputStream) inputStream
                    : new BufferedInputStream(inputStream);
            if (isZipStream(bufferedInputStream)) {
                extractDatabaseBackup(bufferedInputStream, importDir, dbFile.getName());
            } else {
                copyStreamToFile(bufferedInputStream, importDbFile);
            }

            if (!importDbFile.exists() || importDbFile.length() == 0L) {
                throw new IOException("Imported backup does not contain a valid database file");
            }

            closeDatabase();
            backupCurrentDatabaseFiles(backupDir, dbFile);
            try {
                replaceCurrentDatabaseFiles(importDbFile, importWalFile, importShmFile, dbFile);
                validateImportedDatabaseFiles();
            } catch (IOException e) {
                restoreCurrentDatabaseFiles(backupDir, dbFile);
                throw e;
            }
        } finally {
            create();
            if (importDir.exists()) {
                FileUtil.deleteDirectory(importDir);
            }
            if (backupDir.exists()) {
                FileUtil.deleteDirectory(backupDir);
            }
        }
    }

    private static void addFileToZip(ZipOutputStream zipOutputStream, File sourceFile) throws IOException {
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            return;
        }
        zipOutputStream.putNextEntry(new ZipEntry(sourceFile.getName()));
        try (FileInputStream fileInputStream = new FileInputStream(sourceFile)) {
            copyStream(fileInputStream, zipOutputStream);
        }
        zipOutputStream.closeEntry();
    }

    private static boolean isZipStream(BufferedInputStream inputStream) throws IOException {
        inputStream.mark(4);
        int first = inputStream.read();
        int second = inputStream.read();
        int third = inputStream.read();
        int fourth = inputStream.read();
        inputStream.reset();
        return first == 0x50 && second == 0x4b && third == 0x03 && fourth == 0x04;
    }

    private static void extractDatabaseBackup(InputStream inputStream, File targetDir, String databaseName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String entryName = new File(entry.getName()).getName();
                if (!databaseName.equals(entryName)
                        && !(databaseName + "-wal").equals(entryName)
                        && !(databaseName + "-shm").equals(entryName)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                File outFile = new File(targetDir, entryName);
                copyStreamToFile(zipInputStream, outFile);
                zipInputStream.closeEntry();
            }
        }
    }

    private static void backupCurrentDatabaseFiles(File backupDir, File dbFile) throws IOException {
        backupIfExists(dbFile, new File(backupDir, dbFile.getName()));
        backupIfExists(getWalFile(dbFile), new File(backupDir, dbFile.getName() + "-wal"));
        backupIfExists(getShmFile(dbFile), new File(backupDir, dbFile.getName() + "-shm"));
    }

    private static void backupIfExists(File sourceFile, File backupFile) throws IOException {
        if (sourceFile.exists()) {
            copyFile(sourceFile, backupFile);
        }
    }

    private static void replaceCurrentDatabaseFiles(File importDbFile, File importWalFile, File importShmFile, File dbFile) throws IOException {
        deleteIfExists(dbFile);
        deleteIfExists(getWalFile(dbFile));
        deleteIfExists(getShmFile(dbFile));

        copyFile(importDbFile, dbFile);
        if (importWalFile.exists()) {
            copyFile(importWalFile, getWalFile(dbFile));
        }
        if (importShmFile.exists()) {
            copyFile(importShmFile, getShmFile(dbFile));
        }
    }

    private static void restoreCurrentDatabaseFiles(File backupDir, File dbFile) {
        try {
            deleteIfExists(dbFile);
            deleteIfExists(getWalFile(dbFile));
            deleteIfExists(getShmFile(dbFile));

            File backupDbFile = new File(backupDir, dbFile.getName());
            File backupWalFile = new File(backupDir, dbFile.getName() + "-wal");
            File backupShmFile = new File(backupDir, dbFile.getName() + "-shm");

            if (backupDbFile.exists()) {
                copyFile(backupDbFile, dbFile);
            }
            if (backupWalFile.exists()) {
                copyFile(backupWalFile, getWalFile(dbFile));
            }
            if (backupShmFile.exists()) {
                copyFile(backupShmFile, getShmFile(dbFile));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void validateImportedDatabaseFiles() throws IOException {
        AyuDatabase validationDatabase = null;
        try {
            validationDatabase = buildDatabase(false);
            validationDatabase.getOpenHelper().getWritableDatabase().query("SELECT name FROM sqlite_master LIMIT 1").close();
        } catch (Exception e) {
            throw new IOException("Imported backup is not a compatible Ayu database", e);
        } finally {
            if (validationDatabase != null) {
                try {
                    validationDatabase.close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private static void copyStreamToFile(InputStream inputStream, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory " + parent.getAbsolutePath());
        }
        try (BufferedOutputStream fileOutputStream = new BufferedOutputStream(new FileOutputStream(targetFile))) {
            copyStream(inputStream, fileOutputStream);
        }
    }

    private static void copyFile(File sourceFile, File targetFile) throws IOException {
        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(sourceFile))) {
            copyStreamToFile(inputStream, targetFile);
        }
    }

    private static void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[IO_BUFFER_SIZE];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        outputStream.flush();
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete " + file.getAbsolutePath());
        }
    }

    public static long getDatabaseSize() {
        long size = 0;
        try {
            File dbFile = getDatabaseFile();
            File shmCacheFile = new File(dbFile.getAbsolutePath() + "-shm");
            File walCacheFile = new File(dbFile.getAbsolutePath() + "-wal");
            if (dbFile.exists()) {
                size = dbFile.length();
            }
            if (shmCacheFile.exists()) {
                size += shmCacheFile.length();
            }
            if (walCacheFile.exists()) {
                size += walCacheFile.length();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return size;
    }

    public static long getAyuDatabaseSize() {
        return getDatabaseSize();
    }

    public static long getAttachmentsDirSize() {
        long size = 0;
        try {
            AyuMessagesController.syncAttachmentsPathWithConfig();
            if (AyuMessagesController.attachmentsPath.exists()) {
                size = AndroidUtil.getDirectorySize(AyuMessagesController.attachmentsPath);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return size;
    }

    public static void loadSizes(Runnable callback) {
        Utilities.globalQueue.postRunnable(() -> {
            dbSize = getDatabaseSize();
            attachmentsSize = getAttachmentsDirSize();
            totalSize = dbSize + attachmentsSize;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback, 500);
            }
        });
    }
}
