package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

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
        } catch (JSONException | NumberFormatException | java.time.DateTimeException error) {
            throw new SyncException("Product event payload is malformed", error);
        } catch (IllegalStateException error) {
            throw new SyncException("Product event violates local database invariants", error);
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
