package com.komic.reader;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;

@Database(entities = LibraryDb.Book.class, version = 3, exportSchema = false)
public abstract class LibraryDb extends RoomDatabase {
    static final androidx.room.migration.Migration POSITION_MIGRATION = new androidx.room.migration.Migration(1, 2) {
        @Override public void migrate(@NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE books ADD COLUMN pageFraction REAL NOT NULL DEFAULT 0");
        }
    };

    static final androidx.room.migration.Migration REMOVE_SAMPLES = new androidx.room.migration.Migration(2, 3) {
        @Override public void migrate(@NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("DELETE FROM books WHERE id IN ('sample:harbor','sample:rail','sample:botanist','sample:oldtown')");
        }
    };

    public abstract Shelf shelf();

    @Entity(tableName = "books")
    public static class Book {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String title = "";
        @NonNull public String pages = "[]";
        public int page;
        @ColumnInfo(defaultValue = "0") public float pageFraction;
        public long opened;
    }

    @Dao
    public interface Shelf {
        @Query("SELECT * FROM books ORDER BY rowid") LiveData<List<Book>> observe();
        @Insert(onConflict = OnConflictStrategy.IGNORE) void add(Book... books);
        @Query("DELETE FROM books WHERE id = :id")
        void remove(String id);
        @Query("UPDATE books SET pages = :pages, title = :title, page = MIN(page, :lastPage) WHERE id = :id")
        void updateIndex(String id, String title, String pages, int lastPage);
        @Transaction default void reindex(Book book, int lastPage) {
            add(book);
            updateIndex(book.id, book.title, book.pages, lastPage);
        }
        @Query("UPDATE books SET page = :page, pageFraction = :fraction, opened = :time WHERE id = :id")
        void remember(String id, int page, float fraction, long time);
    }
}
