package com.maeyaion.pos.core.db;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
@Entity(tableName="sale_reconciliation",indices={@Index(value="business_date"),@Index(value="sale_type")})
public final class SaleReconciliationEntity {
 @PrimaryKey @NonNull public final String sale_ref_hash; @NonNull public final String business_date; @NonNull public final String sale_type;
 public final long subtotal_satang,fee_charged_satang,returned_goods_satang,returned_fee_satang,cancelled_goods_satang,cancelled_fee_satang,net_sales_satang;
 @NonNull public final String proof_sha256; @NonNull public final String created_at_utc;
 public SaleReconciliationEntity(@NonNull String sale_ref_hash,@NonNull String business_date,@NonNull String sale_type,long subtotal_satang,long fee_charged_satang,long returned_goods_satang,long returned_fee_satang,long cancelled_goods_satang,long cancelled_fee_satang,long net_sales_satang,@NonNull String proof_sha256,@NonNull String created_at_utc){this.sale_ref_hash=sale_ref_hash;this.business_date=business_date;this.sale_type=sale_type;this.subtotal_satang=subtotal_satang;this.fee_charged_satang=fee_charged_satang;this.returned_goods_satang=returned_goods_satang;this.returned_fee_satang=returned_fee_satang;this.cancelled_goods_satang=cancelled_goods_satang;this.cancelled_fee_satang=cancelled_fee_satang;this.net_sales_satang=net_sales_satang;this.proof_sha256=proof_sha256;this.created_at_utc=created_at_utc;}
}
