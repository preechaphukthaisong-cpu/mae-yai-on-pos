package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface SaleDao {
    @Query("SELECT * FROM sale WHERE id = :id LIMIT 1") SaleEntity findById(String id);
    @Query("SELECT * FROM sale WHERE idem_key = :key LIMIT 1") SaleEntity findByIdempotencyKey(String key);
    @Query("SELECT * FROM sale WHERE content_hash=:hash AND created_at_utc>=:sinceUtc AND created_at_utc<=:untilUtc AND lifecycle_status='COMPLETED' ORDER BY created_at_utc DESC LIMIT 1") SaleEntity findRecentDuplicate(String hash,String sinceUtc,String untilUtc);
    @Query("SELECT * FROM sale ORDER BY created_at_utc DESC LIMIT :limit") List<SaleEntity> latest(int limit);
    @Query("SELECT * FROM sale WHERE payment_status = 'PAID' ORDER BY paid_at_utc DESC LIMIT :limit") List<SaleEntity> paid(int limit);
    @Query("SELECT * FROM sale WHERE type = 'CREDIT' AND payment_status = 'UNPAID' ORDER BY created_at_utc DESC") List<SaleEntity> unpaidCredit();
    @Query("SELECT * FROM sale WHERE customer_id = :customerId AND type = 'CREDIT' ORDER BY created_at_utc DESC") List<SaleEntity> creditForCustomer(String customerId);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(SaleEntity sale);
    @Update int update(SaleEntity sale);
}
