package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/** Explicit first-device enrollment, trusted-device approval, recovery and key rotation workflows. */
public final class StoreKeyEnrollmentService {
    private final SyncApiClient api;
    private final StoreKeyManager storeKeys;
    private final DeviceKeyPairManager deviceKeys;
    private final DeviceIdentityStore identity;

    public StoreKeyEnrollmentService(@NonNull Context context, @NonNull SyncApiClient api) {
        if (context == null || api == null) throw new IllegalArgumentException("Context and API are required");
        Context app = context.getApplicationContext();
        this.api = api;
        this.storeKeys = new StoreKeyManager(app);
        this.deviceKeys = new DeviceKeyPairManager(app);
        this.identity = new DeviceIdentityStore(app);
    }

    /** First trusted installation creates epoch 1 and stores only per-device/recovery wrappers remotely. */
    public void initializeFirstDevice(@NonNull String storeId, @NonNull String recoveryKey)
            throws SyncException {
        long serverEpoch = currentServerEpoch();
        if (serverEpoch != 0) throw new SyncException("Store encryption is already initialized");
        JSONObject devices = api.listDevices();
        try {
            JSONArray rows = devices.getJSONArray("devices");
            if (rows.length() != 1
                    || !identity.getOrCreateDeviceId().equals(rows.getJSONObject(0).getString("id"))) {
                throw new SyncException("Initial key enrollment requires exactly this one registered device; approve additional devices explicitly afterward");
            }
            long epoch = storeKeys.initializeStore(storeId);
            byte[] key = storeKeys.getStoreKey(storeId, epoch);
            try {
                JSONArray wrappers = new JSONArray();
                JSONObject wrapper = new JSONObject();
                wrapper.put("deviceId", identity.getOrCreateDeviceId());
                wrapper.put("wrappedKey", deviceKeys.wrapForDevice(
                        rows.getJSONObject(0).getString("public_key"), key)
                        .length == 0 ? "" : Base64Url.encode(deviceKeys.wrapForDevice(
                                rows.getJSONObject(0).getString("public_key"), key)));
                wrappers.put(wrapper);
                String recoveryWrapper = StoreKeyRecoveryWrapper.wrap(storeId, epoch, recoveryKey, key);
                api.submitStoreKeyEpoch(epoch, wrappers, recoveryWrapper);
            } catch (JSONException error) {
                throw new SyncException("Unable to prepare first device key enrollment", error);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (JSONException error) {
            throw new SyncException("Device registration response is malformed", error);
        }
    }

    /** Installs the current store key only when the backend has a wrapper for this installation. */
    public long installCurrentDeviceKey(@NonNull String storeId) throws SyncException {
        JSONObject response = api.fetchCurrentStoreKeyWrapper();
        try {
            long epoch = exactEpoch(response.get("keyEpoch"));
            byte[] wrapped = Base64Url.decode(response.getString("wrappedKey"));
            byte[] key = deviceKeys.unwrapForThisDevice(wrapped);
            try { storeKeys.importStoreKey(storeId, epoch, key); }
            finally {
                Arrays.fill(key, (byte) 0);
                Arrays.fill(wrapped, (byte) 0);
            }
            return epoch;
        } catch (JSONException error) {
            throw new SyncException("Current store key wrapper response is malformed", error);
        }
    }

    /** Recovery path for a device that has no usable device wrapper. */
    public long recoverCurrentDeviceKey(@NonNull String storeId, @NonNull String recoveryKey)
            throws SyncException {
        long epoch = currentServerEpoch();
        if (epoch < 1) throw new SyncException("Store encryption has not been initialized");
        JSONObject response = api.fetchRecoveryStoreKeyWrapper(epoch);
        try {
            if (exactEpoch(response.get("keyEpoch")) != epoch) {
                throw new SyncException("Recovery wrapper epoch does not match the store");
            }
            byte[] key = StoreKeyRecoveryWrapper.unwrap(storeId, epoch, recoveryKey,
                    response.getString("wrappedKey"));
            try { storeKeys.importStoreKey(storeId, epoch, key); }
            finally { Arrays.fill(key, (byte) 0); }
            return epoch;
        } catch (JSONException error) {
            throw new SyncException("Recovery wrapper response is malformed", error);
        }
    }

    /** Approves a new device after the operator compares and confirms its displayed fingerprint. */
    public void approveDevice(@NonNull String targetDeviceId, @NonNull String confirmedFingerprint)
            throws SyncException {
        long epoch = currentServerEpoch();
        if (epoch < 1) throw new SyncException("Store key is not initialized");
        JSONObject response = api.listDevices();
        try {
            JSONArray devices = response.getJSONArray("devices");
            JSONObject target = null;
            for (int i = 0; i < devices.length(); i++) {
                JSONObject candidate = devices.getJSONObject(i);
                if (targetDeviceId.equals(candidate.getString("id"))) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) throw new SyncException("Target device is not registered or is revoked");
            String fingerprint = target.getString("key_fingerprint");
            if (confirmedFingerprint == null
                    || !fingerprint.equalsIgnoreCase(confirmedFingerprint.trim())) {
                throw new SyncException("Target device fingerprint does not match the operator's confirmation");
            }
            byte[] key = storeKeys.getStoreKey(identityStoreId(), epoch);
            try {
                byte[] wrapped = deviceKeys.wrapForDevice(target.getString("public_key"), key);
                api.approveDeviceKeyWrapper(targetDeviceId, epoch, Base64Url.encode(wrapped));
                Arrays.fill(wrapped, (byte) 0);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (JSONException error) {
            throw new SyncException("Device list response is malformed", error);
        }
    }

    /** Rotates the store key and re-wraps it for every active device before publishing the epoch. */
    public long rotateStoreKey(@NonNull String storeId, @NonNull String recoveryKey)
            throws SyncException {
        return rotate(storeId, recoveryKey, null);
    }

    /** Rotation and revocation are one backend transaction; the revoked device receives no new key. */
    public long rotateAndRevokeDevice(@NonNull String storeId, @NonNull String recoveryKey,
            @NonNull String targetDeviceId) throws SyncException {
        if (!isUuid(targetDeviceId) || targetDeviceId.equals(identity.getOrCreateDeviceId())) {
            throw new SyncException("A valid other device must be selected for revocation");
        }
        return rotate(storeId, recoveryKey, targetDeviceId);
    }

    private long rotate(String storeId, String recoveryKey, String revokeDeviceId)
            throws SyncException {
        long serverEpoch = currentServerEpoch();
        if (serverEpoch < 1) throw new SyncException("Store key is not initialized");
        long localEpoch = storeKeys.getCurrentEpoch(storeId);
        final long nextEpoch;
        if (localEpoch == serverEpoch) nextEpoch = storeKeys.rotateStoreKey(storeId);
        else if (localEpoch == serverEpoch + 1) nextEpoch = localEpoch; // Retry after an uncertain network result.
        else throw new SyncException("Local and server key epochs diverged; recovery is required");

        byte[] key = storeKeys.getStoreKey(storeId, nextEpoch);
        try {
            JSONArray wrappers = createWrappers(api.listDevices().getJSONArray("devices"),
                    key, revokeDeviceId);
            String recoveryWrapper = StoreKeyRecoveryWrapper.wrap(storeId, nextEpoch, recoveryKey, key);
            if (revokeDeviceId == null) {
                api.submitStoreKeyEpoch(nextEpoch, wrappers, recoveryWrapper);
            } else {
                api.revokeDeviceWithKeyRotation(revokeDeviceId, nextEpoch, wrappers, recoveryWrapper);
            }
            return nextEpoch;
        } catch (JSONException error) {
            throw new SyncException("Unable to prepare store key rotation", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private JSONArray createWrappers(JSONArray devices, byte[] key, String excludedDeviceId)
            throws JSONException, SyncException {
        JSONArray wrappers = new JSONArray();
        boolean currentIncluded = false;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            String id = device.getString("id");
            if (id.equals(excludedDeviceId)) continue;
            String publicKey = device.getString("public_key");
            byte[] wrapped = deviceKeys.wrapForDevice(publicKey, key);
            JSONObject item = new JSONObject();
            item.put("deviceId", id);
            item.put("wrappedKey", Base64Url.encode(wrapped));
            Arrays.fill(wrapped, (byte) 0);
            wrappers.put(item);
            if (id.equals(identity.getOrCreateDeviceId())) currentIncluded = true;
        }
        if (!currentIncluded || wrappers.length() == 0) {
            throw new SyncException("Key rotation must include this device and at least one active device");
        }
        return wrappers;
    }

    private long currentServerEpoch() throws SyncException {
        try { return exactEpoch(api.getCurrentKeyEpoch().get("keyEpoch")); }
        catch (JSONException error) { throw new SyncException("Server key epoch response is malformed", error); }
    }

    private String identityStoreId() throws SyncException {
        // Store ID is bound to the authenticated local Room cursor; caller cannot choose another store.
        throw new SyncException("Use the overload that supplies the bound store ID for device approval");
    }

    private static long exactEpoch(Object value) throws SyncException {
        try {
            long epoch = new java.math.BigDecimal(value.toString()).longValueExact();
            if (epoch < 0) throw new ArithmeticException("negative epoch");
            return epoch;
        } catch (RuntimeException error) {
            throw new SyncException("Store key epoch is invalid", error);
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return java.util.UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException error) { return false; }
    }

    private static final class Base64Url {
        static String encode(byte[] value) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }
        static byte[] decode(String value) throws SyncException {
            try {
                byte[] result = java.util.Base64.getUrlDecoder().decode(value);
                if (!encode(result).equals(value)) throw new IllegalArgumentException("non-canonical");
                return result;
            } catch (IllegalArgumentException error) {
                throw new SyncException("Wrapped store key encoding is invalid", error);
            }
        }
    }
}
