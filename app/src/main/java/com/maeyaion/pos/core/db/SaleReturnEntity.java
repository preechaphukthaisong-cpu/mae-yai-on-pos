package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName="sale_return", foreignKeys=@ForeignKey(entity=SaleEntity.class,parentColumns="id",childColumns="sale_id",onDelete=ForeignKey.RESTRICT), indices={@Index(value={"sale_id","created_at_utc"})})
public final class SaleReturnEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String sale_id;
    @NonNull public final String reason;
    public final long cash_refund_satang;
    public final long debt_reduction_satang;
    public final long fee_reversal_satang;
    public final String recipient_name;
    @NonNull public final String operator_label;
    @NonNull public final String created_at_utc;
    public SaleReturnEntity(@NonNull String id, @NonNull String sale_id, @NonNull String reason, long cash_refund_satang, long debt_reduction_satang, long fee_reversal_satang, String recipient_name, @NonNull String operator_label, @NonNull String created_at_utc) {
        this.id=id;
        this.sale_id=sale_id;
        this.reason=reason;
        this.cash_refund_satang=cash_refund_satang;
        this.debt_reduction_satang=debt_reduction_satang;
        this.fee_reversal_satang=fee_reversal_satang;
        this.recipient_name=recipient_name;
        this.operator_label=operator_label;
        this.created_at_utc=created_at_utc;
    }
}
