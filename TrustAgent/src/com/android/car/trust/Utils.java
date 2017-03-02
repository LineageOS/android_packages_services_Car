package com.android.car.trust;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import java.nio.ByteBuffer;
import java.util.UUID;

public class Utils {

    public static byte[] getBytes(long l) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.putLong(0, l);
        return buffer.array();
    }

    public static long getLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.put(bytes);
        buffer.flip();
        return buffer.getLong();
    }

    public static BluetoothGattCharacteristic getCharacteristic(int uuidRes,
            BluetoothGattService service, Context context) {
        return service.getCharacteristic(UUID.fromString(context.getString(uuidRes)));
    }
}
