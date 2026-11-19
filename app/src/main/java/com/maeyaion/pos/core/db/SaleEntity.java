package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "sale",
        foreignKeys = @ForeignKey(entity = CustomerEntity.class, parentColumns = "id",
                childColumns = "customer_id", onDelete = ForeignKey.RESTRICT,
                onUpdate = ForeignKey.RESTRICT),
        indices = {@Index(value = "idem_key", unique = true),
                @Index(value = "created_at_utc"), @Index(value = "content_hash")})
public final class SaleEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull @ColumnInfo(name = "idem_key") public final String idemKey;
    @NonNull public final String type;
    @NonNull @ColumnInfo(name = "payment_status") public final String paymentStatus;
    @NonNull @ColumnInfo(name = "lifecycle_status") public final String lifecycleStatus;
    @ColumnInfo(name = "customer_id") public final String customerId;
    @ColumnInfo(name = "subtotal_satang") public final long subtotalSatang;
    @ColumnInfo(name = "fee_total_satang") public final long feeTotalSatang;
    @ColumnInfo(name = "total_satang") public final long totalSatang;
    @ColumnInfo(name = "cash_received_satang") public final Long cashReceivedSatang;
    @ColumnInfo(name = "change_satang") public final Long changeSatang;
    @NonNull @ColumnInfo(name = "content_hash") public final String contentHash;
    @NonNull @ColumnInfo(name = "created_at_utc") public final String createdAtUtc;
    @ColumnInfo(name = "paid_at_utc") public final String paidAtUtc;
    @ColumnInfo(name = "settled_by_reversal_at_utc") public final String settledByReversalAtUtc;

    public SaleEntity(@NonNull String id, @NonNull String idemKey, @NonNull String type,
            @NonNull String paymentStatus, @NonNull String lifecycleStatus, String customerId,
            long subtotalSatang, long feeTotalSatang, long totalSatang,
            Long cashReceivedSatang, Long changeSatang, @NonNull String contentHash,
            @NonNull String createdAtUtc, String paidAtUtc, String settledByReversalAtUtc) {
        this.id = id; this.idemKey = idemKey; this.type = type;
        this.paymentStatus = paymentStatus; this.lifecycleStatus = lifecycleStatus;
        this.customerId = customerId; this.subtotalSatang = subtotalSatang;
        this.feeTotalSatang = feeTotalSatang; this.totalSatang = totalSatang;
        this.cashReceivedSatang = cashReceivedSatang; this.changeSatang = changeSatang;
        this.contentHash = contentHash; this.createdAtUtc = createdAtUtc;
        this.paidAtUtc = paidAtUtc; this.settledByReversalAtUtc = settledByReversalAtUtc;
    }
}
