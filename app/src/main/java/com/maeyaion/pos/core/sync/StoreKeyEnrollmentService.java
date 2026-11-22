package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
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
                byte[] wrapped = deviceKeys.wrapForDevice(
                        rows.getJSONObject(0).getString("public_key"), key);
                try { wrapper.put("wrappedKey", Base64Url.encode(wrapped)); }
                finally { Arrays.fill(wrapped, (byte) 0); }
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

    /**
     * Installs every historical key epoch approved for this device. Historical epochs are
     * required to decrypt retained event-log entries created before the latest rotation.
     */
    public long installAllApprovedDeviceKeys(@NonNull String storeId) throws SyncException {
        long currentEpoch = currentServerEpoch();
        if (currentEpoch < 1 || currentEpoch > 10000) {
            throw new SyncException("Server key epoch is absent or exceeds the supported history limit");
        }
        JSONObject response = api.fetchDeviceKeyWrappers();
        try {
            JSONArray wrappers = response.getJSONArray("wrappers");
            if (wrappers.length() != currentEpoch) {
                throw new SyncException("This device is missing one or more historical key approvals");
            }
            for (int i = 0; i < wrappers.length(); i++) {
                JSONObject item = wrappers.getJSONObject(i);
                long epoch = exactEpoch(item.get("keyEpoch"));
                if (epoch != i + 1L) {
                    throw new SyncException("Device key wrapper history is incomplete or out of order");
                }
                byte[] wrapped = Base64Url.decode(item.getString("wrappedKey"));
                byte[] key = deviceKeys.unwrapForThisDevice(wrapped);
                try { storeKeys.importStoreKey(storeId, epoch, key); }
                finally {
                    Arrays.fill(key, (byte) 0);
                    Arrays.fill(wrapped, (byte) 0);
                }
            }
            if (storeKeys.getCurrentEpoch(storeId) != currentEpoch) {
                throw new SyncException("Installed store key history does not reach the server's current epoch");
            }
            return currentEpoch;
        } catch (JSONException error) {
            throw new SyncException("Device key wrapper history response is malformed", error);
        }
    }

    /** Compatibility entry point; installs the full approved key history, not only the latest key. */
    public long installCurrentDeviceKey(@NonNull String storeId) throws SyncException {
        return installAllApprovedDeviceKeys(storeId);
    }

    /** Recovery path for a device that has no usable device wrappers. */
    public long recoverAllDeviceKeyEpochs(@NonNull String storeId,
            @NonNull String recoveryKey) throws SyncException {
        long currentEpoch = currentServerEpoch();
        if (currentEpoch < 1 || currentEpoch > 10000) {
            throw new SyncException("Store key history is absent or exceeds the supported recovery limit");
        }
        // Import oldest to newest. StoreKeyManager permits a missing historical epoch
        // without lowering the current-epoch pointer.
        for (long epoch = 1; epoch <= currentEpoch; epoch++) {
            JSONObject response = api.fetchRecoveryStoreKeyWrapper(epoch);
            try {
                if (exactEpoch(response.get("keyEpoch")) != epoch) {
                    throw new SyncException("Recovery wrapper epoch does not match the requested history entry");
                }
                byte[] key = StoreKeyRecoveryWrapper.unwrap(storeId, epoch, recoveryKey,
                        response.getString("wrappedKey"));
                try { storeKeys.importStoreKey(storeId, epoch, key); }
                finally { Arrays.fill(key, (byte) 0); }
            } catch (JSONException error) {
                throw new SyncException("Recovery wrapper response is malformed", error);
            }
        }
        if (storeKeys.getCurrentEpoch(storeId) != currentEpoch) {
            throw new SyncException("Recovered store key history does not reach the server's current epoch");
        }
        return currentEpoch;
    }

    /** Compatibility entry point; recovery restores every epoch required by retained events. */
    public long recoverCurrentDeviceKey(@NonNull String storeId,
            @NonNull String recoveryKey) throws SyncException {
        return recoverAllDeviceKeyEpochs(storeId, recoveryKey);
    }

    /** Approves a new device after the operator compares and confirms its displayed fingerprint. */
    public void approveDevice(@NonNull String storeId, @NonNull String targetDeviceId,
            @NonNull String confirmedFingerprint) throws SyncException {
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
            if (epoch > 10000) throw new SyncException("Store key history exceeds the supported device enrollment limit");
            java.util.List<byte[]> historicalKeys = new java.util.ArrayList<>();
            try {
                // Preflight every epoch before making the first server-side approval.
                for (long keyEpoch = 1; keyEpoch <= epoch; keyEpoch++) {
                    historicalKeys.add(storeKeys.getStoreKey(storeId, keyEpoch));
                }
                String publicKey = target.getString("public_key");
                for (int i = 0; i < historicalKeys.size(); i++) {
                    long keyEpoch = i + 1L;
                    byte[] wrapped = deviceKeys.wrapForDevice(publicKey, historicalKeys.get(i));
                    try {
                        api.approveDeviceKeyWrapper(targetDeviceId, keyEpoch, Base64Url.encode(wrapped));
                    } finally {
                        Arrays.fill(wrapped, (byte) 0);
                    }
                }
            } finally {
                for (byte[] key : historicalKeys) Arrays.fill(key, (byte) 0);
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
        long pendingEpoch = storeKeys.getPendingRotationEpoch(storeId);

        if (pendingEpoch > 0 && pendingEpoch == serverEpoch) {
            // The previous request committed but its response/confirmation may have been lost.
            verifyCommittedEpoch(storeId, serverEpoch);
            if (revokeDeviceId != null) {
                if (isDeviceActive(revokeDeviceId)) {
                    throw new SyncException("Server committed a key epoch but did not revoke the requested device");
                }
            }
            storeKeys.confirmRotation(storeId, serverEpoch);
            return serverEpoch;
        }

        final long nextEpoch;
        if (pendingEpoch > 0) {
            if (pendingEpoch != serverEpoch + 1 || localEpoch != pendingEpoch) {
                throw new SyncException("Pending store-key rotation diverged from the server epoch");
            }
            nextEpoch = pendingEpoch;
        } else if (localEpoch == serverEpoch) {
            nextEpoch = storeKeys.rotateStoreKey(storeId);
        } else if (localEpoch == serverEpoch + 1) {
            // Recover a process interruption after local key persistence but before its marker.
            storeKeys.markPendingRotation(storeId, localEpoch);
            nextEpoch = localEpoch;
        } else {
            throw new SyncException("Local and server key epochs diverged; recovery is required");
        }

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
            verifyCommittedEpoch(storeId, nextEpoch);
            if (revokeDeviceId != null && isDeviceActive(revokeDeviceId)) {
                throw new SyncException("Key rotation completed but the target device remains active");
            }
            storeKeys.confirmRotation(storeId, nextEpoch);
            return nextEpoch;
        } catch (JSONException error) {
            throw new SyncException("Unable to prepare store key rotation", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void verifyCommittedEpoch(String storeId, long expectedEpoch) throws SyncException {
        JSONObject wrapperResponse = api.fetchCurrentStoreKeyWrapper();
        byte[] wrapped = null;
        byte[] remoteKey = null;
        byte[] localKey = null;
        try {
            if (exactEpoch(wrapperResponse.get("keyEpoch")) != expectedEpoch) {
                throw new SyncException("Server current key epoch does not match the prepared rotation");
            }
            wrapped = Base64Url.decode(wrapperResponse.getString("wrappedKey"));
            remoteKey = deviceKeys.unwrapForThisDevice(wrapped);
            localKey = storeKeys.getStoreKey(storeId, expectedEpoch);
            if (!MessageDigest.isEqual(remoteKey, localKey)) {
                throw new SyncException("Server committed a different store key than this device prepared");
            }
        } catch (JSONException error) {
            throw new SyncException("Committed store key wrapper response is malformed", error);
        } finally {
            if (wrapped != null) Arrays.fill(wrapped, (byte) 0);
            if (remoteKey != null) Arrays.fill(remoteKey, (byte) 0);
            if (localKey != null) Arrays.fill(localKey, (byte) 0);
        }
    }

    private boolean isDeviceActive(String deviceId) throws SyncException {
        try {
            JSONArray devices = api.listDevices().getJSONArray("devices");
            for (int i = 0; i < devices.length(); i++) {
                if (deviceId.equals(devices.getJSONObject(i).getString("id"))) return true;
            }
            return false;
        } catch (JSONException error) {
            throw new SyncException("Device list response is malformed", error);
        }
    }

    private JSONArray createWrappers(JSONArray devices, byte[] key, String excludedDeviceId)
            throws JSONException, SyncException {
        JSONArray wrappers = new JSONArray();
        boolean currentIncluded = false;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            String id = device.getString("id");
            if (id.equals(excludedDeviceId) || !device.optBoolean("key_enrolled", false)) continue;
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
