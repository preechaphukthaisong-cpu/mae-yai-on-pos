package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface CustomerDao {
    @Query("SELECT * FROM customer WHERE id = :id LIMIT 1") CustomerEntity findById(String id);
    @Query("SELECT * FROM customer WHERE name_key = :nameKey AND identity_key = :identityKey LIMIT 1")
    CustomerEntity findByNormalizedIdentity(String nameKey, String identityKey);

    @Query("SELECT * FROM customer WHERE name_key LIKE :prefix || '%' ORDER BY name LIMIT 20")
    java.util.List<CustomerEntity> searchByNamePrefix(String prefix);

    @Query("SELECT * FROM customer ORDER BY name COLLATE NOCASE") java.util.List<CustomerEntity> listAll();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(CustomerEntity customer);

    @Update int update(CustomerEntity customer);
}