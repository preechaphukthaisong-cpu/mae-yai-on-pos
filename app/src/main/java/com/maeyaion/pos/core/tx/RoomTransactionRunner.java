package com.maeyaion.pos.core.tx;
import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;
import java.util.Objects;
import java.util.concurrent.Callable;
public final class RoomTransactionRunner implements TransactionRunner {
    private final RoomDatabase database;
    public RoomTransactionRunner(@NonNull RoomDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }
    @Override public <T> T runInTransaction(@NonNull Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            return database.runInTransaction(operation);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TransactionExecutionException("Database transaction failed", exception);
        }
    }
    public static final class TransactionExecutionException extends RuntimeException {
        public TransactionExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
