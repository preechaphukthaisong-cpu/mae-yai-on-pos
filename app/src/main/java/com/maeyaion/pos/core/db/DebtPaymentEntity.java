package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "debt_payment",
        foreignKeys = @ForeignKey(entity = SaleEntity.class, parentColumns = "id",
                childColumns = "sale_id", onDelete = ForeignKey.RESTRICT),
        indices = {@Index(value = "sale_id", unique = true), @Index(value = "paid_at_utc")})
public final class DebtPaymentEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String sale_id;
    public final long amount_satang;
    @NonNull public final String paid_at_utc;
    public DebtPaymentEntity(@NonNull String id, @NonNull String sale_id,
            long amount_satang, @NonNull String paid_at_utc) {
        this.id=id; this.sale_id=sale_id; this.amount_satang=amount_satang; this.paid_at_utc=paid_at_utc;
    }
}
