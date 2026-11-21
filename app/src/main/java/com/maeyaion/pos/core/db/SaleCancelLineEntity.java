package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName="sale_cancel_line", foreignKeys={@ForeignKey(entity=SaleCancelEntity.class,parentColumns="id",childColumns="cancel_id",onDelete=ForeignKey.RESTRICT),@ForeignKey(entity=SaleLineEntity.class,parentColumns="id",childColumns="sale_line_id",onDelete=ForeignKey.RESTRICT)}, indices={@Index(value={"sale_line_id"})})
public final class SaleCancelLineEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String cancel_id;
    @NonNull public final String sale_line_id;
    public final long qty_milli;
    public final long value_satang;
    public final long fee_satang;
    public final long restock;
    public final String non_restock_reason;
    public final String evidence_local_ref;
    public final String evidence_content_hash;
    public SaleCancelLineEntity(@NonNull String id, @NonNull String cancel_id, @NonNull String sale_line_id, long qty_milli, long value_satang, long fee_satang, long restock, String non_restock_reason, String evidence_local_ref, String evidence_content_hash) {
        this.id=id;
        this.cancel_id=cancel_id;
        this.sale_line_id=sale_line_id;
        this.qty_milli=qty_milli;
        this.value_satang=value_satang;
        this.fee_satang=fee_satang;
        this.restock=restock;
        this.non_restock_reason=non_restock_reason;
        this.evidence_local_ref=evidence_local_ref;
        this.evidence_content_hash=evidence_content_hash;
    }
}
