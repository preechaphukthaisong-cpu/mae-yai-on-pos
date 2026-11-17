package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface CustomerDao {
    @Query("SELECT * FROM customer WHERE name_key = :nameKey AND identity_key = :identityKey LIMIT 1")
    CustomerEntity findByNormalizedIdentity(String nameKey, String identityKey);

    @Query("SELECT * FROM customer WHERE name_key LIKE :prefix || '%' ORDER BY name LIMIT 20")
    java.util.List<CustomerEntity> searchByNamePrefix(String prefix);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(CustomerEntity customer);
}