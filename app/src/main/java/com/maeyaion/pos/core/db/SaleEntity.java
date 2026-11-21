package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "sale",
        foreignKeys = @ForeignKey(entity = CustomerEntity.class, parentColumns = "id",
                childColumns = "customer_id", onDelete = ForeignKey.RESTRICT),
        indices = {@Index(value = "idem_key", unique = true),
                @Index(value = "created_at_utc"),
                @Index(value = {"payment_status", "created_at_utc"}),
                @Index(value = {"customer_id", "payment_status"}),
                @Index(value = {"content_hash", "created_at_utc"})})
public final class SaleEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String idem_key;
    @NonNull public final String type;
    @NonNull public final String payment_status;
    @NonNull public final String lifecycle_status;
    public final String customer_id;
    public final long subtotal_satang;
    public final long fee_total_satang;
    public final long total_satang;
    public final Long cash_received_satang;
    public final Long change_satang;
    @NonNull public final String content_hash;
    @NonNull public final String created_at_utc;
    public final String paid_at_utc;
    public final String settled_by_reversal_at_utc;

    public SaleEntity(@NonNull String id, @NonNull String idem_key, @NonNull String type,
            @NonNull String payment_status, @NonNull String lifecycle_status, String customer_id,
            long subtotal_satang, long fee_total_satang, long total_satang,
            Long cash_received_satang, Long change_satang, @NonNull String content_hash,
            @NonNull String created_at_utc, String paid_at_utc, String settled_by_reversal_at_utc) {
        this.id=id; this.idem_key=idem_key; this.type=type; this.payment_status=payment_status;
        this.lifecycle_status=lifecycle_status; this.customer_id=customer_id;
        this.subtotal_satang=subtotal_satang; this.fee_total_satang=fee_total_satang;
        this.total_satang=total_satang; this.cash_received_satang=cash_received_satang;
        this.change_satang=change_satang; this.content_hash=content_hash;
        this.created_at_utc=created_at_utc; this.paid_at_utc=paid_at_utc;
        this.settled_by_reversal_at_utc=settled_by_reversal_at_utc;
    }
}
