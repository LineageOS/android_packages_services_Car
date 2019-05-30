/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.trust;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.car.trust.TrustedDeviceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.car.Utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;

/**
 * The part of the Car service that enables the Trusted device feature.  Trusted Device is a feature
 * where a remote device is enrolled as a trusted device that can authorize an Android user in lieu
 * of the user entering a password or PIN.
 * <p>
 * It is comprised of the {@link CarTrustAgentEnrollmentService} for handling enrollment and
 * {@link CarTrustAgentUnlockService} for handling unlock/auth.
 *
 */
public class CarTrustedDeviceService implements CarServiceBase {
    private static final String TAG = CarTrustedDeviceService.class.getSimpleName();
    private static final String UNIQUE_ID_KEY = "CTABM_unique_id";
    private static final String PREF_ENCRYPTION_KEY_PREFIX = "CTABM_encryption_key";
    private static final String KEY_ALIAS = "Ukey2Key";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final int RANDOM_NAME_LENGTH = 6;
    private final Context mContext;
    private CarTrustAgentEnrollmentService mCarTrustAgentEnrollmentService;
    private CarTrustAgentUnlockService mCarTrustAgentUnlockService;
    private CarTrustAgentBleManager mCarTrustAgentBleManager;
    private SharedPreferences mTrustAgentTokenPreferences;
    private UUID mUniqueId;
    private String mRandomName;

    public CarTrustedDeviceService(Context context) {
        mContext = context;
        mCarTrustAgentBleManager = new CarTrustAgentBleManager(context);
        mCarTrustAgentEnrollmentService = new CarTrustAgentEnrollmentService(mContext, this,
                mCarTrustAgentBleManager);
        mCarTrustAgentUnlockService = new CarTrustAgentUnlockService(this,
                mCarTrustAgentBleManager);
    }

    @Override
    public synchronized void init() {
        mCarTrustAgentEnrollmentService.init();
        mCarTrustAgentUnlockService.init();
    }

    @Override
    public synchronized void release() {
        mCarTrustAgentBleManager.cleanup();
        mCarTrustAgentEnrollmentService.release();
        mCarTrustAgentUnlockService.release();
    }

    /**
     * Returns the internal {@link CarTrustAgentEnrollmentService} instance.
     */
    public CarTrustAgentEnrollmentService getCarTrustAgentEnrollmentService() {
        return mCarTrustAgentEnrollmentService;
    }

    /**
     * Returns the internal {@link CarTrustAgentUnlockService} instance.
     */
    public CarTrustAgentUnlockService getCarTrustAgentUnlockService() {
        return mCarTrustAgentUnlockService;
    }

    /**
     * Returns User Id for the given token handle
     *
     * @param handle The handle corresponding to the escrow token
     * @return User id corresponding to the handle
     */
    int getUserHandleByTokenHandle(long handle) {
        return getSharedPrefs().getInt(String.valueOf(handle), -1);
    }

    void onRemoteDeviceConnected(BluetoothDevice device) {
        mCarTrustAgentEnrollmentService.onRemoteDeviceConnected(device);
        mCarTrustAgentUnlockService.onRemoteDeviceConnected(device);
    }

    void onRemoteDeviceDisconnected(BluetoothDevice device) {
        mCarTrustAgentEnrollmentService.onRemoteDeviceDisconnected(device);
        mCarTrustAgentUnlockService.onRemoteDeviceDisconnected(device);
    }

    void onDeviceNameRetrieved(String deviceName) {
        mCarTrustAgentEnrollmentService.onDeviceNameRetrieved(deviceName);
    }

    void cleanupBleService() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "cleanupBleService");
        }
        mCarTrustAgentBleManager.stopGattServer();
        mCarTrustAgentBleManager.stopEnrollmentAdvertising();
        mCarTrustAgentBleManager.stopUnlockAdvertising();
    }

    SharedPreferences getSharedPrefs() {
        if (mTrustAgentTokenPreferences != null) {
            return mTrustAgentTokenPreferences;
        }
        mTrustAgentTokenPreferences = mContext.getSharedPreferences(
                mContext.getString(R.string.token_handle_shared_preferences), Context.MODE_PRIVATE);
        return mTrustAgentTokenPreferences;
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarTrustedDeviceService*");
        int uid = ActivityManager.getCurrentUser();
        writer.println("current user id: " + uid);
        List<TrustedDeviceInfo> deviceInfos = mCarTrustAgentEnrollmentService
                .getEnrolledDeviceInfosForUser(uid);
        writer.println(getDeviceInfoListString(uid, deviceInfos));
        mCarTrustAgentEnrollmentService.dump(writer);
        mCarTrustAgentUnlockService.dump(writer);
    }

    private static String getDeviceInfoListString(int uid, List<TrustedDeviceInfo> deviceInfos) {
        StringBuilder sb = new StringBuilder();
        sb.append("device list of (user : ").append(uid).append("):");
        if (deviceInfos != null && deviceInfos.size() > 0) {
            for (int i = 0; i < deviceInfos.size(); i++) {
                sb.append("\n\tdevice# ").append(i + 1).append(" : ")
                    .append(deviceInfos.get(i).toString());
            }
        } else {
            sb.append("\n\tno device listed");
        }
        return sb.toString();
    }

    /**
     * Get the unique id for head unit. Persists on device until factory reset.
     *
     * @return unique id, or null if unable to retrieve generated id (this should never happen)
     */
    @Nullable
    UUID getUniqueId() {
        if (mUniqueId != null) {
            return mUniqueId;
        }

        SharedPreferences prefs = getSharedPrefs();
        if (prefs.contains(UNIQUE_ID_KEY)) {
            mUniqueId = UUID.fromString(
                    prefs.getString(UNIQUE_ID_KEY, null));
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Found existing trusted unique id: "
                        + prefs.getString(UNIQUE_ID_KEY, ""));
            }
        } else {
            mUniqueId = UUID.randomUUID();
            if (!prefs.edit().putString(UNIQUE_ID_KEY, mUniqueId.toString()).commit()) {
                mUniqueId = null;
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Generated new trusted unique id: "
                        + prefs.getString(UNIQUE_ID_KEY, ""));
            }
        }

        return mUniqueId;
    }

    /**
     * Get communication encryption key for the given device
     *
     * @param deviceId id of trusted device
     * @return encryption key, null if device id is not recognized
     */
    @Nullable
    byte[] getEncryptionKey(String deviceId) {
        SharedPreferences prefs = getSharedPrefs();
        if (!prefs.contains(deviceId)) {
            return null;
        }
        byte[] encryptedKey = Base64.decode(
                prefs.getString(PREF_ENCRYPTION_KEY_PREFIX + deviceId, null),
                Base64.DEFAULT);
        return decryptWithKeyStore(KEY_ALIAS, encryptedKey);
    }

    /**
     * Save encryption key for the given device
     *
     * @param deviceId did of trusted device
     * @param encryptionKey encryption key
     * @return {@code true} if the operation succeeded
     */
    boolean saveEncryptionKey(String deviceId, byte[] encryptionKey) {
        byte[] encryptedKey = encryptWithKeyStore(KEY_ALIAS, encryptionKey);
        if (encryptedKey == null) {
            return false;
        }

        return getSharedPrefs()
                .edit()
                .putString(PREF_ENCRYPTION_KEY_PREFIX + deviceId,
                        Base64.encodeToString(encryptedKey, Base64.DEFAULT))
                .commit();
    }

    /**
     * Get generated random name for enrollment
     *
     * @return a random name for enrollment
     */
    String getRandomName() {
        if (mRandomName == null) {
            // Create random RANDOM_NAME_LENGTH digit number for name
            mRandomName = Utils.generateRandomNumberString(RANDOM_NAME_LENGTH);
        }

        return mRandomName;
    }

    /**
     * Encrypt value with designated key
     *
     * @param keyAlias KeyStore alias for key to use
     * @param value a value to encrypt
     * @return encrypted value, null if unable to encrypt
     */
    @Nullable
    byte[] encryptWithKeyStore(String keyAlias, byte[] value) {
        if (value == null) {
            return null;
        }

        Key key = getKeyStoreKey(keyAlias);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(value);
        } catch (IllegalBlockSizeException
                | BadPaddingException
                | NoSuchAlgorithmException
                | NoSuchPaddingException
                | IllegalStateException
                | InvalidKeyException e) {
            Log.e(TAG, "Unable to encrypt value with key " + keyAlias, e);
            return null;
        }
    }

    /**
     * Decrypt value with designated key
     *
     * @param keyAlias KeyStore alias for key to use
     * @param value encrypted value
     * @return decrypted value, null if unable to decrypt
     */
    @Nullable
    byte[] decryptWithKeyStore(String keyAlias, byte[] value) {
        if (value == null) {
            return null;
        }

        try {
            Key key = getKeyStoreKey(keyAlias);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(value);
        } catch (IllegalBlockSizeException
                | BadPaddingException
                | NoSuchAlgorithmException
                | NoSuchPaddingException
                | IllegalStateException
                | InvalidKeyException e) {
            Log.e(TAG, "Unable to decrypt value with key " + keyAlias, e);
            return null;
        }
    }

    private Key getKeyStoreKey(String keyAlias) {
        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (!keyStore.containsAlias(keyAlias)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
                keyGenerator.init(
                        new KeyGenParameterSpec.Builder(keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                keyGenerator.generateKey();
            }
            return keyStore.getKey(keyAlias, null);

        } catch (KeyStoreException
                | NoSuchAlgorithmException
                | UnrecoverableKeyException
                | NoSuchProviderException
                | CertificateException
                | IOException
                | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Unable to retrieve key " + keyAlias + " from KeyStore.", e);
            throw new IllegalStateException(e);
        }
    }
}
