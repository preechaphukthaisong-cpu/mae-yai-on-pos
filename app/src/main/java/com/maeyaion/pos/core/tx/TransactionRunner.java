package com.maeyaion.pos.core.tx;
import java.util.concurrent.Callable;
public interface TransactionRunner {
    <T> T runInTransaction(Callable<T> operation);
}
