package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface DebtPaymentDao {
    @Query("SELECT * FROM debt_payment WHERE sale_id = :saleId LIMIT 1") DebtPaymentEntity findForSale(String saleId);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(DebtPaymentEntity payment);
}
