package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.CustomerEntity;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.db.DailySummaryEntity;
import com.maeyaion.pos.core.db.ProductDailyEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Typed inbound domain replay. This implementation deliberately supports only product
 * snapshots until the financial aggregate handlers are implemented and independently
 * qualified. Unsupported event types fail closed; the inbox/cursor transaction rolls back.
 */
public final class SyncDomainEventApplier implements SyncInboxProcessor.DomainEventApplier {
    private final AppDatabase database;

    public SyncDomainEventApplier(@NonNull AppDatabase database) {
        if (database == null) throw new IllegalArgumentException("Database is required");
        this.database = database;
    }

    @Override
    public void apply(@NonNull SyncInboxEntity event) throws Exception {
        if (!database.inTransaction()) {
            throw new SyncException("Domain replay must run inside the inbox Room transaction");
        }
        if (event == null) throw new IllegalArgumentException("Inbox event is required");
        if (!"upsert".equals(event.operation)) {
            throw new SyncException("Unsupported remote operation for " + event.entity_type);
        }
        if ("product".equals(event.entity_type)) {
            applyProduct(event);
            return;
        }
        if ("sale".equals(event.entity_type)) {
            applySale(event);
            return;
        }
        if ("sale_return".equals(event.entity_type)) {
            applySaleReturn(event);
            return;
        }
        if ("sale_cancel".equals(event.entity_type)) {
            applySaleCancel(event);
            return;
        }
        if ("debt_payment_batch".equals(event.entity_type)) {
            applyDebtPaymentBatch(event);
            return;
        }
        throw new SyncException("No typed replay handler is registered for entity type: " + event.entity_type);
    }

    private void applyProduct(SyncInboxEntity event) throws SyncException {
        if (!event.entity_id.equals(event.entity_id.trim()) || event.entity_id.isEmpty()) {
            throw new SyncException("Product event SKU is invalid");
        }
        try {
            JSONObject envelope = new JSONObject(event.payload_json);
            if (exactInt(envelope.get("schemaVersion"), "schemaVersion") != SyncEventEnvelope.CURRENT_SCHEMA_VERSION) {
                throw new SyncException("Unsupported product event schema version");
            }
            JSONObject data = envelope.getJSONObject("data");
            String sku = requiredString(data, "sku");
            if (!event.entity_id.equals(sku)) throw new SyncException("Product event ID does not match its SKU");
            String barcode = requiredString(data, "barcode");
            String name = requiredString(data, "name");
            String unit = requiredString(data, "unit");
            String sourceType = requiredString(data, "barcode_source_type");
            if (!"SYSTEM".equals(sourceType) && !"MANUFACTURER".equals(sourceType)) {
                throw new SyncException("Product barcode source type is invalid");
            }
            String registryName = requiredString(data, "barcode_registry_display_name");
            long registryPrice = exactLong(data.get("barcode_registry_price_satang"), "barcode_registry_price_satang");
            String registryCreated = requiredString(data, "barcode_registry_created_at_utc");
            long price = exactLong(data.get("price_satang"), "price_satang");
            long cost = exactLong(data.get("cost_satang"), "cost_satang");
            long stock = exactLong(data.get("stock_qty_milli"), "stock_qty_milli");
            Object limitValue = data.get("stock_limit_qty_milli");
            Long stockLimit = limitValue == JSONObject.NULL ? null : exactLong(limitValue, "stock_limit_qty_milli");
            int active = exactInt(data.get("is_active"), "is_active");
            String created = requiredString(data, "created_at_utc");
            String updated = requiredString(data, "updated_at_utc");
            if (price < 0 || cost < 0 || stock < 0 || (stockLimit != null && stockLimit < 0)
                    || registryPrice < 0 || (active != 0 && active != 1)) {
                throw new SyncException("Product event contains an out-of-range value");
            }
            Instant.parse(created);
            Instant.parse(updated);
            Instant.parse(registryCreated);

            BarcodeRegistryEntity registry = database.barcodeRegistryDao().findByBarcode(barcode);
            if (registry == null) {
                database.barcodeRegistryDao().insert(new BarcodeRegistryEntity(
                        barcode, registryName, registryPrice, sourceType, registryCreated));
            } else if (!registry.displayName.equals(registryName)
                    || registry.priceSatang != registryPrice
                    || !registry.sourceType.equals(sourceType)
                    || !registry.createdAtUtc.equals(registryCreated)) {
                throw new SyncException("Barcode is already registered with different immutable provenance");
            }

            ProductEntity existing = database.productDao().findBySku(sku);
            ProductEntity incoming = new ProductEntity(sku, barcode, name, price, cost, unit,
                    stockLimit, stock, active, created, updated);
            long beforeStock = existing == null ? 0L : existing.stockQtyMilli;
            if (existing == null) {
                ProductEntity barcodeOwner = database.productDao().findByBarcode(barcode);
                if (barcodeOwner != null && !barcodeOwner.sku.equals(sku)) {
                    throw new SyncException("Barcode is already assigned to another product");
                }
                database.productDao().insert(incoming);
            } else {
                if (!existing.barcode.equals(barcode) || !existing.createdAtUtc.equals(created)) {
                    throw new SyncException("Product identity changed; refusing destructive replay");
                }
                if (database.productDao().update(incoming) != 1) {
                    throw new SyncException("Product snapshot update failed");
                }
            }
            if (beforeStock != stock) {
                database.stockLogDao().append(new StockLogEntity(event.event_id, sku,
                        beforeStock, stock, Math.subtractExact(stock, beforeStock),
                        "SYNC_REPLAY", event.event_id, updated));
            }
        } catch (JSONException | NumberFormatException | java.time.DateTimeException error) {
            throw new SyncException("Product event payload is malformed", error);
        } catch (IllegalStateException error) {
            throw new SyncException("Product event violates local database invariants", error);
        }
    }

    /** Replays a complete sale aggregate, its customer dependency, journal and daily projections. */
    private void applySale(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject envelope = new JSONObject(event.payload_json);
            if (exactInt(envelope.get("schemaVersion"), "schemaVersion")
                    != SyncEventEnvelope.CURRENT_SCHEMA_VERSION) {
                throw new SyncException("Unsupported sale event schema version");
            }
            JSONObject data = envelope.getJSONObject("data");
            String saleId = requiredString(data, "sale_id");
            if (!event.entity_id.equals(saleId)) throw new SyncException("Sale event ID does not match sale payload");
            if (database.saleDao().findById(saleId) != null
                    || database.saleDao().findByIdempotencyKey(requiredString(data, "idempotency_key")) != null) {
                throw new SyncException("Sale already exists locally under a different replay event");
            }
            String type = requiredString(data, "type");
            String paymentStatus = requiredString(data, "payment_status");
            String lifecycleStatus = requiredString(data, "lifecycle_status");
            if (!"CASH".equals(type) && !"CREDIT".equals(type)) throw new SyncException("Sale type is invalid");
            if (!"PAID".equals(paymentStatus) && !"UNPAID".equals(paymentStatus)) throw new SyncException("Sale payment status is invalid");
            if (!"COMPLETED".equals(lifecycleStatus)) throw new SyncException("New sale event has an invalid lifecycle status");
            String customerId = data.isNull("customer_id") ? null : requiredString(data, "customer_id");
            if ("CREDIT".equals(type)) {
                if (customerId == null || data.isNull("customer")) throw new SyncException("Credit sale is missing its encrypted customer dependency");
                JSONObject customerData = data.getJSONObject("customer");
                if (!customerId.equals(requiredString(customerData, "id"))) {
                    throw new SyncException("Credit sale customer identity does not match");
                }
                String name = requiredString(customerData, "name");
                String nameKey = requiredString(customerData, "name_key");
                String identityKey = requiredString(customerData, "identity_key");
                String customerCreated = requiredString(customerData, "created_at_utc");
                String customerUpdated = requiredString(customerData, "updated_at_utc");
                Instant.parse(customerCreated);
                Instant.parse(customerUpdated);
                CustomerEntity existingCustomer = database.customerDao().findById(customerId);
                if (existingCustomer == null) {
                    database.customerDao().insert(new CustomerEntity(customerId, name, nameKey,
                            identityKey, customerCreated, customerUpdated));
                } else if (!existingCustomer.identity_key.equals(identityKey)
                        || !existingCustomer.created_at_utc.equals(customerCreated)) {
                    throw new SyncException("Customer identity conflicts with the local customer record");
                }
            } else if (customerId != null || !data.isNull("customer")) {
                throw new SyncException("Cash sale cannot contain a customer dependency");
            }

            long subtotal = exactLong(data.get("subtotal_satang"), "subtotal_satang");
            long fee = exactLong(data.get("fee_total_satang"), "fee_total_satang");
            long total = exactLong(data.get("total_satang"), "total_satang");
            Long cashReceived = nullableLong(data, "cash_received_satang");
            Long change = nullableLong(data, "change_satang");
            if (subtotal < 0 || fee < 0 || total != Math.addExact(subtotal, fee)
                    || ("CASH".equals(type) && paymentStatus != null && !"PAID".equals(paymentStatus))
                    || ("CREDIT".equals(type) && !"UNPAID".equals(paymentStatus))) {
                throw new SyncException("Sale financial totals or payment state are inconsistent");
            }
            String contentHash = requiredString(data, "content_hash");
            String createdAt = requiredString(data, "created_at_utc");
            String paidAt = data.isNull("paid_at_utc") ? null : requiredString(data, "paid_at_utc");
            Instant createdInstant = Instant.parse(createdAt);
            if ("CASH".equals(type) && paidAt == null) throw new SyncException("Cash sale must have a paid timestamp");
            if ("CREDIT".equals(type) && paidAt != null) throw new SyncException("Credit sale cannot have a paid timestamp");

            JSONArray linesJson = data.getJSONArray("lines");
            if (linesJson.length() < 1 || linesJson.length() > 500) throw new SyncException("Sale line count is invalid");
            List<SaleLineEntity> lines = new java.util.ArrayList<>();
            long lineTotal = 0;
            java.util.HashSet<String> lineIds = new java.util.HashSet<>();
            java.util.HashSet<String> skus = new java.util.HashSet<>();
            for (int i = 0; i < linesJson.length(); i++) {
                JSONObject item = linesJson.getJSONObject(i);
                String lineId = requiredString(item, "id");
                int lineNo = exactInt(item.get("line_no"), "line_no");
                String sku = requiredString(item, "sku");
                String name = requiredString(item, "name_snap");
                long price = exactLong(item.get("price_snap_satang"), "price_snap_satang");
                long cost = exactLong(item.get("cost_snap_satang"), "cost_snap_satang");
                long quantity = exactLong(item.get("qty_milli"), "qty_milli");
                long amount = exactLong(item.get("line_total_satang"), "line_total_satang");
                if (lineNo != i + 1 || !lineIds.add(lineId) || !skus.add(sku)
                        || database.productDao().findBySku(sku) == null
                        || price < 0 || cost < 0 || quantity <= 0 || amount < 0) {
                    throw new SyncException("Sale line identity or values are invalid");
                }
                lineTotal = Math.addExact(lineTotal, amount);
                lines.add(new SaleLineEntity(lineId, saleId, lineNo, sku, name,
                        price, cost, quantity, amount));
            }
            if (lineTotal != subtotal) throw new SyncException("Sale line totals do not equal the sale subtotal");

            SaleEntity sale = new SaleEntity(saleId, requiredString(data, "idempotency_key"),
                    type, paymentStatus, lifecycleStatus, customerId, subtotal, fee, total,
                    cashReceived, change, contentHash, createdAt, paidAt, null);
            database.saleDao().insert(sale);
            database.saleLineDao().insertAll(lines);

            String businessDate = createdInstant.atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
            database.dailySummaryDao().ensure(new DailySummaryEntity(
                    businessDate,0,0,0,0,0,0,0,0,0,0,0,0,createdAt));
            long cashGoods = "CASH".equals(type) ? subtotal : 0L;
            long creditGoods = "CREDIT".equals(type) ? subtotal : 0L;
            if (database.dailySummaryDao().addSale(businessDate, cashGoods, creditGoods,
                    fee, total, createdAt) != 1) {
                throw new SyncException("Sale daily summary projection failed");
            }
            for (SaleLineEntity line : lines) {
                database.productDailyDao().ensure(new ProductDailyEntity(
                        businessDate,line.sku,0,0,0,0,0,0,0,0));
                if (database.productDailyDao().addSale(businessDate,line.sku,
                        line.qty_milli,line.line_total_satang) != 1) {
                    throw new SyncException("Sale product daily projection failed");
                }
            }
            String auditPayload = event.payload_json;
            database.opLogDao().append(new OpLogEntity(java.util.UUID.randomUUID().toString(),
                    "SYNC_REPLAY_SALE", "SALE", saleId, auditPayload, event.received_at_utc));
            database.backupJournalDao().append(new BackupJournalEntity(0L, event.event_id,
                    "SYNC_REPLAY_SALE", auditPayload, sha256Hex(auditPayload),
                    SyncEventEnvelope.CURRENT_SCHEMA_VERSION, event.received_at_utc, 0));
        } catch (JSONException | NumberFormatException | java.time.DateTimeException error) {
            throw new SyncException("Sale event payload is malformed", error);
        } catch (ArithmeticException error) {
            throw new SyncException("Sale event totals overflow", error);
        } catch (IllegalStateException error) {
            throw new SyncException("Sale replay violates local database invariants", error);
        }
    }

    private void applySaleReturn(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject data = unwrap(event);
            String returnId = requiredString(data, "return_id");
            String saleId = requiredString(data, "sale_id");
            if (!event.entity_id.equals(returnId)) throw new SyncException("Return event ID does not match payload");
            SaleEntity sale = database.saleDao().findById(saleId);
            if (sale == null || "CANCELLED".equals(sale.lifecycle_status)
                    || !database.saleCancelDao().findForSale(saleId).equals(null)) {
                throw new SyncException("Return references a missing or cancelled sale");
            }
            String reason = requiredString(data, "reason");
            String operator = requiredString(data, "operator_label");
            String createdAt = requiredString(data, "created_at_utc");
            Instant created = Instant.parse(createdAt);
            long cashRefund = exactLong(data.get("cash_refund_satang"), "cash_refund_satang");
            long debtReduction = exactLong(data.get("debt_reduction_satang"), "debt_reduction_satang");
            long feeReversal = exactLong(data.get("fee_reversal_satang"), "fee_reversal_satang");
            String recipient = data.isNull("recipient_name") ? null : requiredString(data, "recipient_name");
            JSONArray rowsJson = data.getJSONArray("lines");
            if (rowsJson.length() < 1 || rowsJson.length() > 500) throw new SyncException("Return line count is invalid");
            List<SaleReturnLineEntity> rows = new java.util.ArrayList<>();
            long goods = 0;
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < rowsJson.length(); i++) {
                JSONObject item = rowsJson.getJSONObject(i);
                String id = requiredString(item, "id");
                String saleLineId = requiredString(item, "sale_line_id");
                SaleLineEntity source = database.saleLineDao().findById(saleLineId);
                long qty = exactLong(item.get("qty_milli"), "qty_milli");
                long value = exactLong(item.get("value_satang"), "value_satang");
                long fee = exactLong(item.get("fee_satang"), "fee_satang");
                int restock = exactInt(item.get("restock"), "restock");
                String nonRestock = item.isNull("non_restock_reason") ? null
                        : requiredString(item, "non_restock_reason");
                if (!ids.add(id) || source == null || !source.sale_id.equals(saleId)
                        || qty <= 0 || value < 0 || fee < 0 || (restock != 0 && restock != 1)
                        || (restock == 0 && nonRestock == null)) {
                    throw new SyncException("Return line identity or values are invalid");
                }
                long already = database.saleReturnLineDao().returnedQty(saleLineId);
                long cancelled = database.saleCancelLineDao().cancelledQty(saleLineId);
                if (Math.addExact(already, qty) > source.qty_milli - cancelled) {
                    throw new SyncException("Returned quantity exceeds the remaining sale quantity");
                }
                goods = Math.addExact(goods, value);
                rows.add(new SaleReturnLineEntity(id, returnId, saleLineId, qty, value, fee,
                        restock, nonRestock, null, null));
            }
            long reversal = Math.addExact(goods, feeReversal);
            if (feeReversal < 0 || cashRefund < 0 || debtReduction < 0
                    || Math.addExact(cashRefund, debtReduction) != reversal) {
                throw new SyncException("Return financial allocation is inconsistent");
            }
            boolean cashSale = "CASH".equals(sale.type);
            if (cashSale ? (cashRefund != reversal || debtReduction != 0)
                    : ("UNPAID".equals(sale.payment_status)
                        ? (cashRefund != 0 || debtReduction != reversal)
                        : (cashRefund != reversal || debtReduction != 0))) {
                throw new SyncException("Return refund/debt allocation does not match sale payment state");
            }
            database.saleReturnDao().insert(new com.maeyaion.pos.core.db.SaleReturnEntity(
                    returnId, saleId, reason, cashRefund, debtReduction, feeReversal,
                    recipient, operator, createdAt));
            database.saleReturnLineDao().insertAll(rows);
            String businessDate = created.atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
            database.dailySummaryDao().ensure(new DailySummaryEntity(
                    businessDate,0,0,0,0,0,0,0,0,0,0,0,0,createdAt));
            if (database.dailySummaryDao().addReturn(businessDate, cashSale ? goods : 0,
                    cashSale ? 0 : goods, cashSale ? feeReversal : 0,
                    cashSale ? 0 : feeReversal, reversal, createdAt) != 1) {
                throw new SyncException("Return daily summary projection failed");
            }
            for (SaleReturnLineEntity row : rows) {
                SaleLineEntity source = database.saleLineDao().findById(row.sale_line_id);
                database.productDailyDao().ensure(new ProductDailyEntity(
                        businessDate, source.sku,0,0,0,0,0,0,0,0));
                if (database.productDailyDao().addReturn(businessDate, source.sku,
                        row.qty_milli, row.value_satang) != 1) {
                    throw new SyncException("Return product daily projection failed");
                }
            }
            if (debtReduction > 0 && database.saleBalanceDao().outstanding(saleId) == 0) {
                database.saleDao().update(new SaleEntity(sale.id, sale.idem_key, sale.type,
                        sale.payment_status, sale.lifecycle_status, sale.customer_id,
                        sale.subtotal_satang, sale.fee_total_satang, sale.total_satang,
                        sale.cash_received_satang, sale.change_satang, sale.content_hash,
                        sale.created_at_utc, sale.paid_at_utc, createdAt));
            }
            appendAudit(event, "SYNC_REPLAY_RETURN", "SALE", saleId);
        } catch (JSONException | java.time.DateTimeException error) {
            throw new SyncException("Return event payload is malformed", error);
        } catch (ArithmeticException error) {
            throw new SyncException("Return event totals overflow", error);
        }
    }

    private void applySaleCancel(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject data = unwrap(event);
            String cancelId = requiredString(data, "cancel_id");
            String saleId = requiredString(data, "sale_id");
            if (!event.entity_id.equals(cancelId)) throw new SyncException("Cancellation event ID does not match payload");
            SaleEntity sale = database.saleDao().findById(saleId);
            if (sale == null || "CANCELLED".equals(sale.lifecycle_status)
                    || database.saleCancelDao().findForSale(saleId) != null) {
                throw new SyncException("Cancellation references a missing or already cancelled sale");
            }
            String reason = requiredString(data, "reason");
            String operator = requiredString(data, "operator_label");
            String createdAt = requiredString(data, "created_at_utc");
            Instant created = Instant.parse(createdAt);
            long cashRefund = exactLong(data.get("cash_refund_satang"), "cash_refund_satang");
            long debtReduction = exactLong(data.get("debt_reduction_satang"), "debt_reduction_satang");
            long feeReversal = exactLong(data.get("fee_reversal_satang"), "fee_reversal_satang");
            String recipient = data.isNull("recipient_name") ? null : requiredString(data, "recipient_name");
            JSONArray rowsJson = data.getJSONArray("lines");
            if (rowsJson.length() < 1 || rowsJson.length() > 500) throw new SyncException("Cancellation line count is invalid");
            List<com.maeyaion.pos.core.db.SaleCancelLineEntity> rows = new java.util.ArrayList<>();
            long goods = 0;
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < rowsJson.length(); i++) {
                JSONObject item = rowsJson.getJSONObject(i);
                String id = requiredString(item, "id");
                String saleLineId = requiredString(item, "sale_line_id");
                SaleLineEntity source = database.saleLineDao().findById(saleLineId);
                long qty = exactLong(item.get("qty_milli"), "qty_milli");
                long value = exactLong(item.get("value_satang"), "value_satang");
                long fee = exactLong(item.get("fee_satang"), "fee_satang");
                int restock = exactInt(item.get("restock"), "restock");
                String nonRestock = item.isNull("non_restock_reason") ? null
                        : requiredString(item, "non_restock_reason");
                if (!ids.add(id) || source == null || !source.sale_id.equals(saleId)
                        || qty <= 0 || value < 0 || fee < 0 || (restock != 0 && restock != 1)
                        || (restock == 0 && nonRestock == null)
                        || qty > source.qty_milli - database.saleReturnLineDao().returnedQty(saleLineId)) {
                    throw new SyncException("Cancellation line identity or values are invalid");
                }
                goods = Math.addExact(goods, value);
                rows.add(new com.maeyaion.pos.core.db.SaleCancelLineEntity(id, cancelId,
                        saleLineId, qty, value, fee, restock, nonRestock, null, null));
            }
            long reversal = Math.addExact(goods, feeReversal);
            if (feeReversal < 0 || cashRefund < 0 || debtReduction < 0
                    || Math.addExact(cashRefund, debtReduction) != reversal) {
                throw new SyncException("Cancellation financial allocation is inconsistent");
            }
            boolean unpaidCredit = "CREDIT".equals(sale.type) && "UNPAID".equals(sale.payment_status);
            if (unpaidCredit ? (cashRefund != 0 || debtReduction != reversal)
                    : (cashRefund != reversal || debtReduction != 0)) {
                throw new SyncException("Cancellation refund/debt allocation does not match sale payment state");
            }
            database.saleCancelDao().insert(new com.maeyaion.pos.core.db.SaleCancelEntity(
                    cancelId, saleId, reason, cashRefund, debtReduction, feeReversal,
                    recipient, operator, createdAt));
            database.saleCancelLineDao().insertAll(rows);
            String businessDate = created.atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
            database.dailySummaryDao().ensure(new DailySummaryEntity(
                    businessDate,0,0,0,0,0,0,0,0,0,0,0,0,createdAt));
            boolean cashSale = "CASH".equals(sale.type);
            if (database.dailySummaryDao().addCancel(businessDate, cashSale ? goods : 0,
                    cashSale ? 0 : goods, cashSale ? feeReversal : 0,
                    cashSale ? 0 : feeReversal, reversal, createdAt) != 1) {
                throw new SyncException("Cancellation daily summary projection failed");
            }
            for (com.maeyaion.pos.core.db.SaleCancelLineEntity row : rows) {
                SaleLineEntity source = database.saleLineDao().findById(row.sale_line_id);
                database.productDailyDao().ensure(new ProductDailyEntity(
                        businessDate, source.sku,0,0,0,0,0,0,0,0));
                if (database.productDailyDao().addCancel(businessDate, source.sku,
                        row.qty_milli, row.value_satang) != 1) {
                    throw new SyncException("Cancellation product daily projection failed");
                }
            }
            String settledAt = sale.settled_by_reversal_at_utc;
            if (unpaidCredit && database.saleBalanceDao().outstanding(saleId) == 0) settledAt = createdAt;
            database.saleDao().update(new SaleEntity(sale.id, sale.idem_key, sale.type,
                    sale.payment_status, "CANCELLED", sale.customer_id, sale.subtotal_satang,
                    sale.fee_total_satang, sale.total_satang, sale.cash_received_satang,
                    sale.change_satang, sale.content_hash, sale.created_at_utc,
                    sale.paid_at_utc, settledAt));
            appendAudit(event, "SYNC_REPLAY_CANCEL", "SALE", saleId);
        } catch (JSONException | java.time.DateTimeException error) {
            throw new SyncException("Cancellation event payload is malformed", error);
        } catch (ArithmeticException error) {
            throw new SyncException("Cancellation event totals overflow", error);
        }
    }

    private void applyDebtPaymentBatch(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject data = unwrap(event);
            JSONArray payments = data.getJSONArray("payments");
            if (payments.length() < 1 || payments.length() > 500) {
                throw new SyncException("Debt payment batch size is invalid");
            }
            java.util.HashSet<String> saleIds = new java.util.HashSet<>();
            java.util.HashSet<String> paymentIds = new java.util.HashSet<>();
            String paidAt = event.received_at_utc;
            for (int i = 0; i < payments.length(); i++) {
                JSONObject item = payments.getJSONObject(i);
                String paymentId = requiredString(item, "payment_id");
                String saleId = requiredString(item, "sale_id");
                long amount = exactLong(item.get("amount_satang"), "amount_satang");
                SaleEntity sale = database.saleDao().findById(saleId);
                if (!paymentIds.add(paymentId) || !saleIds.add(saleId) || sale == null
                        || !"CREDIT".equals(sale.type) || !"UNPAID".equals(sale.payment_status)
                        || amount <= 0 || amount != database.saleBalanceDao().outstanding(saleId)
                        || database.debtPaymentDao().findForSale(saleId) != null) {
                    throw new SyncException("Debt payment does not match the outstanding credit balance");
                }
                database.debtPaymentDao().insert(new com.maeyaion.pos.core.db.DebtPaymentEntity(
                        paymentId, saleId, amount, paidAt));
                if (database.saleDao().update(new SaleEntity(sale.id, sale.idem_key, sale.type,
                        "PAID", sale.lifecycle_status, sale.customer_id, sale.subtotal_satang,
                        sale.fee_total_satang, sale.total_satang, null, null, sale.content_hash,
                        sale.created_at_utc, paidAt, sale.settled_by_reversal_at_utc)) != 1) {
                    throw new SyncException("Debt payment sale status update failed");
                }
            }
            appendAudit(event, "SYNC_REPLAY_DEBT_PAYMENT", "DEBT_BATCH", event.entity_id);
        } catch (JSONException error) {
            throw new SyncException("Debt payment batch payload is malformed", error);
        } catch (ArithmeticException error) {
            throw new SyncException("Debt payment amount overflow", error);
        }
    }

    private JSONObject unwrap(SyncInboxEntity event) throws JSONException, SyncException {
        JSONObject envelope = new JSONObject(event.payload_json);
        if (exactInt(envelope.get("schemaVersion"), "schemaVersion")
                != SyncEventEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new SyncException("Unsupported sync event schema version");
        }
        return envelope.getJSONObject("data");
    }

    private void appendAudit(SyncInboxEntity event, String operation, String entityType,
            String entityId) throws SyncException {
        String payload = event.payload_json;
        database.opLogDao().append(new OpLogEntity(java.util.UUID.randomUUID().toString(),
                operation, entityType, entityId, payload, event.received_at_utc));
        database.backupJournalDao().append(new BackupJournalEntity(0L, event.event_id,
                operation, payload, sha256Hex(payload), SyncEventEnvelope.CURRENT_SCHEMA_VERSION,
                event.received_at_utc, 0));
    }

    private static Long nullableLong(JSONObject object, String key) throws JSONException, SyncException {
        if (object.isNull(key)) return null;
        return exactLong(object.get(key), key);
    }

    private static String sha256Hex(String value) throws SyncException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new SyncException("SHA-256 is unavailable", error);
        }
    }

    private static String requiredString(JSONObject object, String key) throws JSONException, SyncException {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new SyncException("Product event field is missing or invalid: " + key);
        }
        return (String) value;
    }

    private static long exactLong(Object value, String key) throws SyncException {
        if (!(value instanceof Number)) throw new SyncException("Product event integer is invalid: " + key);
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new SyncException("Product event integer is out of range: " + key, error);
        }
    }

    private static int exactInt(Object value, String key) throws SyncException {
        long result = exactLong(value, key);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new SyncException("Product event integer is out of range: " + key);
        }
        return (int) result;
    }
}
