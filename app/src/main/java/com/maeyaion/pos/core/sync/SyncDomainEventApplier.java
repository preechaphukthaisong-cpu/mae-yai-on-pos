package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.AppMetaEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.CustomerEntity;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.db.SaleReturnLineEntity;
import com.maeyaion.pos.core.db.DailySummaryEntity;
import com.maeyaion.pos.core.db.ProductDailyEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Typed inbound domain replay. Every supported business aggregate is validated and applied
 * inside the caller's Room transaction. Unknown event types fail closed; the inbox/cursor
 * transaction rolls back without discarding the event.
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
        if ("store_setting".equals(event.entity_type)) {
            applyStoreSetting(event);
            return;
        }
        if ("customer".equals(event.entity_type)) {
            applyCustomer(event);
            return;
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

    /** Replays only explicitly allow-listed store profile settings; local/device secrets are excluded. */
    private void applyStoreSetting(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject data = unwrap(event);
            String key = requiredString(data, "setting_key");
            Object rawValue = data.get("value");
            if (!(rawValue instanceof String)) throw new SyncException("Store setting value must be text");
            String value = (String) rawValue;
            String updatedAt = requiredString(data, "updated_at_utc");
            if (!event.entity_id.equals(key)
                    || !java.util.Arrays.asList("store_name", "store_phone", "store_address",
                            "receipt_footer", "tax_id").contains(key)
                    || value.length() > ("store_address".equals(key) ? 1000
                            : "receipt_footer".equals(key) ? 500
                            : "store_name".equals(key) ? 160 : 32)
                    || value.indexOf('\u0000') >= 0) {
                throw new SyncException("Store setting key or value is not allowed");
            }
            if ("store_phone".equals(key) && !value.isEmpty()
                    && !value.matches("[0-9+() .-]{6,32}")) {
                throw new SyncException("Store phone setting format is invalid");
            }
            if ("tax_id".equals(key) && !value.isEmpty()
                    && !value.matches("[0-9-]{10,20}")) {
                throw new SyncException("Store tax ID format is invalid");
            }
            Instant incomingAt = Instant.parse(updatedAt);
            AppMetaEntity existing = database.appMetaDao().findEntry(key);
            if (existing != null) {
                Instant localAt = Instant.parse(existing.updatedAtUtc);
                if (incomingAt.isBefore(localAt)) {
                    throw new SyncException("Store setting is older than local data; manual resolution required");
                }
                if (incomingAt.equals(localAt)) {
                    if (!existing.value.equals(value)) {
                        throw new SyncException("Store setting changed with the same timestamp; manual resolution required");
                    }
                    return;
                }
            }
            database.appMetaDao().put(new AppMetaEntity(key, value, updatedAt));
            appendAudit(event, "SYNC_REPLAY_STORE_SETTING", "STORE_SETTING", key);
        } catch (JSONException | java.time.DateTimeException | IllegalArgumentException error) {
            throw new SyncException("Store setting event payload is malformed", error);
        }
    }

    /** Replays customer PII only after the inbox decoder has authenticated and decrypted it. */
    private void applyCustomer(SyncInboxEntity event) throws SyncException {
        try {
            JSONObject data = unwrap(event);
            String customerId = requiredString(data, "customer_id");
            if (!event.entity_id.equals(customerId)
                    || !java.util.UUID.fromString(customerId).toString().equalsIgnoreCase(customerId)) {
                throw new SyncException("Customer event identity is invalid");
            }
            String name = requiredString(data, "name");
            String nameKey = requiredString(data, "name_key");
            String identityKey = requiredString(data, "identity_key");
            String createdAt = requiredString(data, "created_at_utc");
            String updatedAt = requiredString(data, "updated_at_utc");
            Instant created = Instant.parse(createdAt);
            Instant updated = Instant.parse(updatedAt);
            if (updated.isBefore(created)) throw new SyncException("Customer update timestamp precedes creation");
            String normalizedName = java.text.Normalizer.normalize(name,
                    java.text.Normalizer.Form.NFC).trim().replaceAll("\\s+", " ")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!normalizedName.equals(nameKey) || name.length() > 200
                    || nameKey.length() > 240 || identityKey.length() > 240) {
                throw new SyncException("Customer normalized identity fields are inconsistent");
            }
            CustomerEntity collision = database.customerDao()
                    .findByNormalizedIdentity(nameKey, identityKey);
            if (collision != null && !collision.id.equals(customerId)) {
                throw new SyncException("Customer normalized identity belongs to another local customer");
            }
            CustomerEntity existing = database.customerDao().findById(customerId);
            CustomerEntity incoming = new CustomerEntity(customerId, name, nameKey,
                    identityKey, createdAt, updatedAt);
            if (existing == null) {
                database.customerDao().insert(incoming);
            } else {
                if (!existing.created_at_utc.equals(createdAt)
                        || !existing.identity_key.equals(identityKey)) {
                    throw new SyncException("Customer immutable identity changed; manual resolution required");
                }
                Instant localUpdated = Instant.parse(existing.updated_at_utc);
                if (updated.isBefore(localUpdated)) {
                    throw new SyncException("Customer update is older than local data; manual resolution required");
                }
                if (updated.equals(localUpdated)) {
                    if (!existing.name.equals(name) || !existing.name_key.equals(nameKey)) {
                        throw new SyncException("Customer changed with the same timestamp; manual resolution required");
                    }
                } else if (database.customerDao().update(incoming) != 1) {
                    throw new SyncException("Customer snapshot update failed");
                }
            }
            appendAudit(event, "SYNC_REPLAY_CUSTOMER", "CUSTOMER", customerId);
        } catch (JSONException | java.time.DateTimeException | IllegalArgumentException error) {
            throw new SyncException("Customer event payload is malformed", error);
        }
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
            if (!data.isNull("stock_movement")) {
                JSONObject movement = data.getJSONObject("stock_movement");
                String operationId = requiredString(movement, "operation_id");
                if (!java.util.UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                    throw new SyncException("Inventory movement operation ID is invalid");
                }
                long movementBefore = exactLong(movement.get("before_qty_milli"), "before_qty_milli");
                long movementDiff = exactLong(movement.get("diff_qty_milli"), "diff_qty_milli");
                long movementAfter = exactLong(movement.get("after_qty_milli"), "after_qty_milli");
                String reason = requiredString(movement, "reason");
                String movementAt = requiredString(movement, "created_at_utc");
                Instant.parse(movementAt);
                if (movementBefore != beforeStock || movementAfter != stock
                        || Math.addExact(movementBefore, movementDiff) != movementAfter
                        || (!"RECEIVE".equals(reason) && !"COUNT_REPLACE".equals(reason))
                        || ("RECEIVE".equals(reason) && movementDiff <= 0)) {
                    throw new SyncException("Inventory movement does not match the product stock transition");
                }
                database.stockLogDao().append(new StockLogEntity(
                        java.util.UUID.randomUUID().toString(), sku, movementBefore,
                        movementAfter, movementDiff, reason, operationId, movementAt));
            } else if (beforeStock != stock) {
                // Sale, return and cancellation events carry stock snapshots but have their own
                // aggregate event. Preserve an explicit reconciliation movement for the projection.
                database.stockLogDao().append(new StockLogEntity(event.event_id, sku,
                        beforeStock, stock, Math.subtractExact(stock, beforeStock),
                        "SYNC_REPLAY", event.event_id, updated));
            }
            appendAudit(event, "SYNC_REPLAY_PRODUCT", "PRODUCT", sku);
        } catch (JSONException | NumberFormatException | java.time.DateTimeException error) {
            throw new SyncException("Product event payload is malformed", error);
        } catch (ArithmeticException error) {
            throw new SyncException("Product stock transition overflow", error);
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
                } else {
                    Instant localUpdated = Instant.parse(existingCustomer.updated_at_utc);
                    Instant remoteUpdated = Instant.parse(customerUpdated);
                    if (remoteUpdated.isAfter(localUpdated)) {
                        if (database.customerDao().update(new CustomerEntity(customerId, name,
                                nameKey, identityKey, customerCreated, customerUpdated)) != 1) {
                            throw new SyncException("Customer snapshot update failed");
                        }
                    } else if (remoteUpdated.equals(localUpdated)
                            && (!existingCustomer.name.equals(name)
                                || !existingCustomer.name_key.equals(nameKey))) {
                        throw new SyncException("Customer changed with the same update timestamp; manual resolution required");
                    }
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
            if (!contentHash.matches("(?i)[a-f0-9]{64}")) throw new SyncException("Sale content hash is invalid");
            String createdAt = requiredString(data, "created_at_utc");
            String paidAt = data.isNull("paid_at_utc") ? null : requiredString(data, "paid_at_utc");
            Instant createdInstant = Instant.parse(createdAt);
            if ("CASH".equals(type)) {
                if (paidAt == null || cashReceived == null || change == null
                        || cashReceived < total || change != cashReceived - total) {
                    throw new SyncException("Cash sale payment and change values are inconsistent");
                }
                Instant.parse(paidAt);
            }
            if ("CREDIT".equals(type) && (paidAt != null || cashReceived != null || change != null)) {
                throw new SyncException("Credit sale cannot contain cash payment values");
            }

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
                    5, event.received_at_utc, 0));
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
                    || database.saleCancelDao().findForSale(saleId) != null) {
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
            long lineFees = 0;
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
                        || (restock == 1 && nonRestock != null)) {
                    throw new SyncException("Return line identity or values are invalid");
                }
                long already = database.saleReturnLineDao().returnedQty(saleLineId);
                long cancelled = database.saleCancelLineDao().cancelledQty(saleLineId);
                if (Math.addExact(already, qty) > source.qty_milli - cancelled
                        || value > source.line_total_satang - database.saleReturnLineDao().returnedValue(saleLineId)
                                - database.saleCancelLineDao().cancelledValue(saleLineId)) {
                    throw new SyncException("Returned quantity or value exceeds the remaining sale amount");
                }
                goods = Math.addExact(goods, value);
                lineFees = Math.addExact(lineFees, fee);
                rows.add(new SaleReturnLineEntity(id, returnId, saleLineId, qty, value, fee,
                        restock, nonRestock, null, null));
            }
            long reversal = Math.addExact(goods, feeReversal);
            if (feeReversal < 0 || lineFees != feeReversal || cashRefund < 0 || debtReduction < 0
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
            long lineFees = 0;
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
                        || (restock == 1 && nonRestock != null)
                        || qty > source.qty_milli - database.saleReturnLineDao().returnedQty(saleLineId)
                                - database.saleCancelLineDao().cancelledQty(saleLineId)
                        || value > source.line_total_satang - database.saleReturnLineDao().returnedValue(saleLineId)
                                - database.saleCancelLineDao().cancelledValue(saleLineId)) {
                    throw new SyncException("Cancellation line identity or values are invalid");
                }
                goods = Math.addExact(goods, value);
                lineFees = Math.addExact(lineFees, fee);
                rows.add(new com.maeyaion.pos.core.db.SaleCancelLineEntity(id, cancelId,
                        saleLineId, qty, value, fee, restock, nonRestock, null, null));
            }
            long reversal = Math.addExact(goods, feeReversal);
            if (feeReversal < 0 || lineFees != feeReversal || cashRefund < 0 || debtReduction < 0
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
            for (SaleLineEntity source : database.saleLineDao().forSale(saleId)) {
                long remaining = source.qty_milli
                        - database.saleReturnLineDao().returnedQty(source.id)
                        - database.saleCancelLineDao().cancelledQty(source.id);
                if (remaining != 0) throw new SyncException("Cancellation did not close every remaining sale line");
            }
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
            String paidAt = requiredString(data, "paid_at_utc");
            Instant.parse(paidAt);
            java.util.HashSet<String> saleIds = new java.util.HashSet<>();
            java.util.HashSet<String> paymentIds = new java.util.HashSet<>();
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
