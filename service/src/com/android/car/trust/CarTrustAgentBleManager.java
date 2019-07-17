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

import android.annotation.IntDef;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.collection.SimpleArrayMap;

import com.android.car.BLEStreamProtos.BLEMessageProto.BLEMessage;
import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType;
import com.android.car.BLEStreamProtos.VersionExchangeProto.BLEVersionExchange;
import com.android.car.R;
import com.android.car.Utils;
import com.android.car.protobuf.InvalidProtocolBufferException;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A BLE Service that is used for communicating with the trusted peer device. This extends from a
 * more generic {@link BleManager} and has more context on the BLE requirements for the Trusted
 * device feature. It has knowledge on the GATT services and characteristics that are specific to
 * the Trusted Device feature.
 */
class CarTrustAgentBleManager extends BleManager {

    private static final String TAG = "CarTrustBLEManager";

    /**
     * The UUID of the Client Characteristic Configuration Descriptor. This descriptor is
     * responsible for specifying if a characteristic can be subscribed to for notifications.
     *
     * @see <a href="https://www.bluetooth.com/specifications/gatt/descriptors/">
     * GATT Descriptors</a>
     */
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** @hide */
    @IntDef(prefix = {"TRUSTED_DEVICE_OPERATION_"}, value = {
            TRUSTED_DEVICE_OPERATION_NONE,
            TRUSTED_DEVICE_OPERATION_ENROLLMENT,
            TRUSTED_DEVICE_OPERATION_UNLOCK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrustedDeviceOperation {
    }

    private static final int TRUSTED_DEVICE_OPERATION_NONE = 0;
    private static final int TRUSTED_DEVICE_OPERATION_ENROLLMENT = 1;
    private static final int TRUSTED_DEVICE_OPERATION_UNLOCK = 2;
    @VisibleForTesting
    static final long BLE_MESSAGE_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(2);

    @TrustedDeviceOperation
    private int mCurrentTrustedDeviceOperation = TRUSTED_DEVICE_OPERATION_NONE;
    private String mOriginalBluetoothName;
    private byte[] mUniqueId;
    private int mMtuSize = 20;
    @VisibleForTesting
    int mBleMessageRetryLimit = 20;
    private final List<BleEventCallback> mBleEventCallbacks = new ArrayList<>();
    private AdvertiseCallback mEnrollmentAdvertisingCallback;
    private SendMessageCallback mSendMessageCallback;

    // Enrollment Service and Characteristic UUIDs
    private UUID mEnrollmentServiceUuid;
    private UUID mEnrollmentClientWriteUuid;
    private UUID mEnrollmentServerWriteUuid;
    private BluetoothGattService mEnrollmentGattService;

    // Unlock Service and Characteristic UUIDs
    private UUID mUnlockServiceUuid;
    private UUID mUnlockClientWriteUuid;
    private UUID mUnlockServerWriteUuid;
    private BluetoothGattService mUnlockGattService;

    private Queue<BLEMessage> mMessageQueue = new LinkedList<>();
    private BLEMessagePayloadStream mBleMessagePayloadStream = new BLEMessagePayloadStream();

    // This is a boolean because there's only one supported version.
    private boolean mIsVersionExchanged;
    @VisibleForTesting
    int mBleMessageRetryStartCount;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mSendRepeatedBleMessage;

    // A map of enrollment/unlock client write uuid -> listener
    private final SimpleArrayMap<UUID, DataReceivedListener> mDataReceivedListeners =
            new SimpleArrayMap<>();

    CarTrustAgentBleManager(Context context) {
        super(context);
    }

    /**
     * This should be called before starting unlock advertising
     */
    void setUniqueId(UUID uniqueId) {
        mUniqueId = Utils.uuidToBytes(uniqueId);
    }

    // Overriding some of the {@link BLEManager} methods to be specific for Trusted Device feature.
    @Override
    public void onRemoteDeviceConnected(BluetoothDevice device) {
        if (mBleEventCallbacks.isEmpty()) {
            Log.e(TAG, "No valid BleEventCallback for trust device.");
            return;
        }

        // Retrieving device name only happens in enrollment, the retrieved device name will be
        // stored in sharedPreference for further use.
        if (mCurrentTrustedDeviceOperation == TRUSTED_DEVICE_OPERATION_ENROLLMENT
                && device.getName() == null) {
            retrieveDeviceName(device);
        }

        mMessageQueue.clear();
        mIsVersionExchanged = false;
        if (mSendRepeatedBleMessage != null) {
            mHandler.removeCallbacks(mSendRepeatedBleMessage);
            mSendRepeatedBleMessage = null;
            mBleMessageRetryStartCount = 0;
        }
        mBleEventCallbacks.forEach(bleEventCallback ->
                bleEventCallback.onRemoteDeviceConnected(device));
    }

    @Override
    public void onRemoteDeviceDisconnected(BluetoothDevice device) {
        mBleEventCallbacks.forEach(bleEventCallback ->
                bleEventCallback.onRemoteDeviceDisconnected(device));

        mMessageQueue.clear();
        mIsVersionExchanged = false;
        mBleMessagePayloadStream.reset();

        if (mSendRepeatedBleMessage != null) {
            mHandler.removeCallbacks(mSendRepeatedBleMessage);
            mSendRepeatedBleMessage = null;
            mBleMessageRetryStartCount = 0;
        }
    }

    @Override
    protected void onDeviceNameRetrieved(@Nullable String deviceName) {
        mBleEventCallbacks.forEach(bleEventCallback ->
                bleEventCallback.onClientDeviceNameRetrieved(deviceName));
    }

    @Override
    protected void onMtuSizeChanged(int size) {
        mMtuSize = size;
    }

    @Override
    public void onCharacteristicWrite(BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic, boolean preparedWrite,
            boolean responseNeeded, int offset, byte[] value) {
        UUID uuid = characteristic.getUuid();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onCharacteristicWrite received uuid: " + uuid);
        }

        if (!mIsVersionExchanged) {
            resolveBLEVersion(device, value, uuid);
            return;
        }

        BLEMessage message;
        try {
            message = BLEMessage.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Can not parse BLE message", e);
            return;
        }

        if (message.getOperation() == OperationType.ACK) {
            handleClientAckMessage(device, uuid);
            return;
        }

        // This write operation is not thread safe individually, but is guarded by the callback
        // here.
        try {
            mBleMessagePayloadStream.write(message);
        } catch (IOException e) {
            Log.e(TAG, "Can write the BLE message's payload", e);
            return;
        }

        if (!mBleMessagePayloadStream.isComplete()) {
            // If it's not complete, make sure the client knows that this message was received.
            sendAcknowledgmentMessage(device, uuid);
            return;
        }

        if (mDataReceivedListeners.containsKey(uuid)) {
            mDataReceivedListeners.get(uuid).onDataReceived(mBleMessagePayloadStream.toByteArray());
        } else {
            Log.e(TAG, "No DataReceviedListener to notify of characteristic write. (uuid"
                    + uuid.toString() + ")");
        }
        mBleMessagePayloadStream.reset();
    }

    @Override
    public void onCharacteristicRead(BluetoothDevice device, int requestId, int offset,
            final BluetoothGattCharacteristic characteristic) {
        // Ignored read requests.
    }

    @VisibleForTesting
    void setBleMessageRetryLimit(int limit) {
        mBleMessageRetryLimit = limit;
    }

    private void resolveBLEVersion(BluetoothDevice device, byte[] value,
            UUID clientCharacteristicUUID) {
        BluetoothGattCharacteristic characteristic =
                getCharacteristicForWrite(clientCharacteristicUUID);

        if (characteristic == null) {
            Log.e(TAG, "Invalid UUID (" + clientCharacteristicUUID
                    + ") during version exchange; disconnecting from remote device.");
            disconnectRemoteDevice();
            return;
        }

        BLEVersionExchange deviceVersion;
        try {
            deviceVersion = BLEVersionExchange.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            disconnectRemoteDevice();
            Log.e(TAG, "Could not parse version exchange message", e);
            return;
        }

        if (!BLEVersionExchangeResolver.hasSupportedVersion(deviceVersion)) {
            Log.e(TAG, "No supported version found during version exchange.");
            disconnectRemoteDevice();
            return;
        }

        BLEVersionExchange headunitVersion = BLEVersionExchangeResolver.makeVersionExchange();
        setValueOnCharacteristicAndNotify(device, headunitVersion.toByteArray(), characteristic);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Sent supported version to the phone.");
        }

        mIsVersionExchanged = true;
    }

    /**
     * Setup the BLE GATT server for Enrollment. The GATT server for Enrollment comprises of one
     * GATT Service and 2 characteristics - one for the phone to write to and one for the head unit
     * to write to.
     */
    void setupEnrollmentBleServer() {
        mEnrollmentServiceUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_service_uuid));
        mEnrollmentClientWriteUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_client_write_uuid));
        mEnrollmentServerWriteUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_server_write_uuid));

        mEnrollmentGattService = new BluetoothGattService(mEnrollmentServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic the connected bluetooth device will write to.
        BluetoothGattCharacteristic clientCharacteristic =
                new BluetoothGattCharacteristic(mEnrollmentClientWriteUuid,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic that this manager will write to.
        BluetoothGattCharacteristic serverCharacteristic =
                new BluetoothGattCharacteristic(mEnrollmentServerWriteUuid,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        addDescriptorToCharacteristic(serverCharacteristic);

        mEnrollmentGattService.addCharacteristic(clientCharacteristic);
        mEnrollmentGattService.addCharacteristic(serverCharacteristic);
    }

    /**
     * Setup the BLE GATT server for Unlocking the Head unit. The GATT server for this phase also
     * comprises of 1 Service and 2 characteristics. However both the token and the handle are sent
     * from the phone to the head unit.
     */
    void setupUnlockBleServer() {
        mUnlockServiceUuid = UUID.fromString(getContext().getString(R.string.unlock_service_uuid));
        mUnlockClientWriteUuid = UUID
                .fromString(getContext().getString(R.string.unlock_client_write_uuid));
        mUnlockServerWriteUuid = UUID
                .fromString(getContext().getString(R.string.unlock_server_write_uuid));

        mUnlockGattService = new BluetoothGattService(mUnlockServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic the connected bluetooth device will write to.
        BluetoothGattCharacteristic clientCharacteristic = new BluetoothGattCharacteristic(
                mUnlockClientWriteUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic that this manager will write to.
        BluetoothGattCharacteristic serverCharacteristic = new BluetoothGattCharacteristic(
                mUnlockServerWriteUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        addDescriptorToCharacteristic(serverCharacteristic);

        mUnlockGattService.addCharacteristic(clientCharacteristic);
        mUnlockGattService.addCharacteristic(serverCharacteristic);
    }

    private void addDescriptorToCharacteristic(BluetoothGattCharacteristic characteristic) {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        characteristic.addDescriptor(descriptor);
    }

    /**
     * Begins advertising for enrollment
     *
     * @param deviceName device name to advertise
     * @param enrollmentAdvertisingCallback callback for advertiser
     */
    void startEnrollmentAdvertising(@Nullable String deviceName,
            AdvertiseCallback enrollmentAdvertisingCallback) {
        if (enrollmentAdvertisingCallback == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Enrollment Advertising not started: "
                        + "enrollmentAdvertisingCallback is null");
            }
            return;
        }
        mCurrentTrustedDeviceOperation = TRUSTED_DEVICE_OPERATION_ENROLLMENT;
        mEnrollmentAdvertisingCallback = enrollmentAdvertisingCallback;
        // Replace name to ensure it is small enough to be advertised
        if (deviceName != null) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (mOriginalBluetoothName == null) {
                mOriginalBluetoothName = adapter.getName();
            }
            adapter.setName(deviceName);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Changing bluetooth adapter name from "
                        + mOriginalBluetoothName + " to " + deviceName);
            }
        }
        startAdvertising(mEnrollmentGattService,
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(true)
                        .addServiceUuid(new ParcelUuid(mEnrollmentServiceUuid))
                        .build(),
                mEnrollmentAdvertisingCallback);
    }

    void stopEnrollmentAdvertising() {
        if (mOriginalBluetoothName != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Changing bluetooth adapter name back to "
                        + mOriginalBluetoothName);
            }
            BluetoothAdapter.getDefaultAdapter().setName(mOriginalBluetoothName);
        }
        if (mEnrollmentAdvertisingCallback != null) {
            stopAdvertising(mEnrollmentAdvertisingCallback);
        }
    }

    void startUnlockAdvertising() {
        if (mUniqueId == null) {
            Log.e(TAG, "unique id is null");
            return;
        }
        mCurrentTrustedDeviceOperation = TRUSTED_DEVICE_OPERATION_UNLOCK;
        startAdvertising(mUnlockGattService,
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceData(new ParcelUuid(mUnlockServiceUuid), mUniqueId)
                        .addServiceUuid(new ParcelUuid(mUnlockServiceUuid))
                        .build(),
                mUnlockAdvertisingCallback);
    }

    void stopUnlockAdvertising() {
        mCurrentTrustedDeviceOperation = TRUSTED_DEVICE_OPERATION_NONE;
        stopAdvertising(mUnlockAdvertisingCallback);
    }

    void disconnectRemoteDevice() {
        stopGattServer();
    }

    void sendUnlockMessage(BluetoothDevice device, byte[] message, OperationType operation,
            boolean isPayloadEncrypted, SendMessageCallback callback) {
        BluetoothGattCharacteristic writeCharacteristic = mUnlockGattService
                .getCharacteristic(mUnlockServerWriteUuid);
        mSendMessageCallback = callback;

        sendMessage(device, writeCharacteristic, message, operation, isPayloadEncrypted);
    }

    void sendEnrollmentMessage(BluetoothDevice device, byte[] message, OperationType operation,
            boolean isPayloadEncrypted, SendMessageCallback callback) {
        BluetoothGattCharacteristic writeCharacteristic = mEnrollmentGattService
                .getCharacteristic(mEnrollmentServerWriteUuid);
        mSendMessageCallback = callback;

        sendMessage(device, writeCharacteristic, message, operation, isPayloadEncrypted);
    }

    /**
     * Handles an ACK from the client.
     *
     * <p>An ACK means that the client has successfully received a partial BLEMessage, meaning the
     * next part of the message can be sent.
     *
     * @param device                   The client device.
     * @param clientCharacteristicUUID The UUID of the characteristic on the device that the ACK
     *                                 was written to.
     */
    @VisibleForTesting
    void handleClientAckMessage(BluetoothDevice device, UUID clientCharacteristicUUID) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Received ACK from client. Attempting to write next message in queue. "
                    + "UUID: " + clientCharacteristicUUID);
        }

        BluetoothGattCharacteristic writeCharacteristic =
                getCharacteristicForWrite(clientCharacteristicUUID);

        if (writeCharacteristic == null) {
            Log.e(TAG, "No corresponding write characteristic found for writing next message in"
                    + " queue. UUID: " + clientCharacteristicUUID);
            return;
        }
        if (mSendRepeatedBleMessage != null) {
            mHandler.removeCallbacks(mSendRepeatedBleMessage);
            mSendRepeatedBleMessage = null;
            mBleMessageRetryStartCount = 0;
        }
        // Previous message has been sent successfully so we can start the next message.
        mMessageQueue.remove();
        writeNextMessageInQueue(device, writeCharacteristic);
    }

    /**
     * Sends the given message to the specified device and characteristic.
     * The message will be splited into multiple messages wrapped in BLEMessage proto.
     *
     * @param device             The device to send the message to.
     * @param characteristic     The characteristic to write to.
     * @param message            A message to send.
     * @param operation          The type of operation this message represents.
     * @param isPayloadEncrypted {@code true} if the message is encrypted.
     */
    void sendMessage(BluetoothDevice device, BluetoothGattCharacteristic characteristic,
            byte[] message, OperationType operation, boolean isPayloadEncrypted) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sendMessage to: " + device.getAddress() + "; and characteristic UUID: "
                    + characteristic.getUuid());
        }

        List<BLEMessage> bleMessages = BLEMessageV1Factory.makeBLEMessages(message, operation,
                mMtuSize, isPayloadEncrypted);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sending " + bleMessages.size() + " messages to device");
        }

        mMessageQueue.addAll(bleMessages);
        writeNextMessageInQueue(device, characteristic);
    }

    /**
     * Writes the next message in {@link #mMessageQueue} to the given characteristic.
     *
     * <p>If the message queue is empty, then this method will do nothing.
     */
    private void writeNextMessageInQueue(BluetoothDevice device,
            BluetoothGattCharacteristic characteristic) {
        if (mMessageQueue.isEmpty()) {
            Log.e(TAG, "Call to write next message in queue, but the message queue is empty");
            return;
        }
        // When there is only one message, no ACKs are sent, so we no need to retry based on ACKs.
        if (mMessageQueue.size() == 1) {
            setValueOnCharacteristicAndNotify(device, mMessageQueue.remove().toByteArray(),
                    characteristic);
            return;
        }
        mSendRepeatedBleMessage = new Runnable() {
            @Override
            public void run() {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "BLE message sending... " + "retry count: "
                            + mBleMessageRetryStartCount);
                }
                if (mBleMessageRetryStartCount < mBleMessageRetryLimit) {
                    setValueOnCharacteristicAndNotify(device, mMessageQueue.peek().toByteArray(),
                            characteristic);
                    mBleMessageRetryStartCount++;
                    mHandler.postDelayed(this, BLE_MESSAGE_RETRY_DELAY_MS);
                } else {
                    Log.e(TAG, "Error during BLE message sending - exceeded retry limit.");
                    mHandler.removeCallbacks(this);
                    if (mSendMessageCallback != null) {
                        mSendMessageCallback.onSendMessageFailure();
                    }
                    mSendRepeatedBleMessage = null;
                    mBleMessageRetryStartCount = 0;
                    mMessageQueue.clear();
                }
            }
        };
        mHandler.post(mSendRepeatedBleMessage);
    }

    private void sendAcknowledgmentMessage(BluetoothDevice device, UUID clientCharacteristicUUID) {
        BluetoothGattCharacteristic writeCharacteristic =
                getCharacteristicForWrite(clientCharacteristicUUID);

        if (writeCharacteristic == null) {
            Log.e(TAG, "No corresponding write characteristic found for sending ACK. UUID: "
                    + clientCharacteristicUUID);
            return;
        }

        setValueOnCharacteristicAndNotify(device,
                BLEMessageV1Factory.makeAcknowledgementMessage().toByteArray(),
                writeCharacteristic);
    }

    /**
     * Sets the given message on the specified characteristic.
     *
     * <p>Upon successfully setting of the value, any listeners on the characteristic will be
     * notified that its value has changed.
     *
     * @param device         The device has own the given characteristic.
     * @param message        The message to set as the characteristic's value.
     * @param characteristic The characteristic to set the value on.
     */
    private void setValueOnCharacteristicAndNotify(BluetoothDevice device, byte[] message,
            BluetoothGattCharacteristic characteristic) {
        characteristic.setValue(message);
        notifyCharacteristicChanged(device, characteristic, false);
    }

    /**
     * Returns the characteristic that can be written to based on the given UUID.
     *
     * <p>The UUID will be one that corresponds to either enrollment or unlock. This method will
     * return the write characteristic for enrollment or unlock respectively.
     *
     * @return The write characteristic or {@code null} if the UUID is invalid.
     */
    @Nullable
    private BluetoothGattCharacteristic getCharacteristicForWrite(UUID uuid) {
        if (uuid.equals(mEnrollmentClientWriteUuid)) {
            return mEnrollmentGattService.getCharacteristic(mEnrollmentServerWriteUuid);
        }

        if (uuid.equals(mUnlockClientWriteUuid)) {
            return mUnlockGattService.getCharacteristic(mUnlockServerWriteUuid);
        }

        return null;
    }

    private final AdvertiseCallback mUnlockAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unlock Advertising onStartSuccess");
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Failed to advertise, errorCode: " + errorCode);
            super.onStartFailure(errorCode);
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                return;
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Start unlock advertising fail, retry to advertising..");
            }
            setupUnlockBleServer();
            startUnlockAdvertising();
        }
    };

    /**
     * Adds the given listener to be notified when the characteristic with the given uuid has
     * received data.
     */
    void addDataReceivedListener(UUID uuid, DataReceivedListener listener) {
        mDataReceivedListeners.put(uuid, listener);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Register DataReceivedListener: " + listener + "for uuid "
                    + uuid.toString());
        }
    }

    void removeDataReceivedListener(UUID uuid) {
        if (!mDataReceivedListeners.containsKey(uuid)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No DataReceivedListener for uuid " + uuid.toString()
                        + " to unregister.");
            }
            return;
        }
        mDataReceivedListeners.remove(uuid);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Unregister DataReceivedListener for uuid " + uuid.toString());
        }
    }

    void addBleEventCallback(BleEventCallback callback) {
        mBleEventCallbacks.add(callback);
    }

    void removeBleEventCallback(BleEventCallback callback) {
        if (!mBleEventCallbacks.remove(callback)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Remove BleEventCallback that does not exist.");
            }
        }
    }

    /**
     * Callback to be invoked for enrollment events
     */
    interface SendMessageCallback {

        /**
         * Called when enrollment handshake needs to be terminated
         */
        void onSendMessageFailure();
    }

    /**
     * Callback to be invoked when BLE receives data from remote device.
     */
    interface DataReceivedListener {

        /**
         * Called when data has been received from a remote device.
         *
         * @param value received data
         */
        void onDataReceived(byte[] value);
    }

    /**
     * The interface that device service has to implement to get notified when BLE events occur
     */
    interface BleEventCallback {

        /**
         * Called when a remote device is connected
         *
         * @param device the remote device
         */
        void onRemoteDeviceConnected(BluetoothDevice device);

        /**
         * Called when a remote device is disconnected
         *
         * @param device the remote device
         */
        void onRemoteDeviceDisconnected(BluetoothDevice device);

        /**
         * Called when the device name of the remote device is retrieved
         *
         * @param deviceName device name of the remote device
         */
        void onClientDeviceNameRetrieved(String deviceName);
    }
}
