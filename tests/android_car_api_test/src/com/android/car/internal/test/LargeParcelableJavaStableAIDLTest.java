/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.internal.test;

import static com.google.common.truth.Truth.assertThat;

import android.car.apitest.IStableAIDLTestBinder;
import android.car.apitest.StableAIDLTestLargeParcelable;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.internal.LargeParcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

@SmallTest
public final class LargeParcelableJavaStableAIDLTest {
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final int ARRAY_LENGTH_SMALL = 2048;
    // The current threshold is 4096.
    private static final int ARRAY_LENGTH_BIG = 4099;

    private final Context mContext = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();

    private final TestServiceConnection mServiceConnection = new TestServiceConnection();

    private IStableAIDLTestBinderWrapper mBinder;

    @Before
    public void setUp() throws Exception {
        LargeParcelable.setClassLoader(mContext.getClassLoader());
        Intent intent = new Intent();
        intent.setClassName(mContext, IStableAIDLBinderTestService.class.getName());
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        JavaMockitoHelper.await(mServiceConnection.latch, DEFAULT_TIMEOUT_MS);
    }

    @After
    public void tearDown() {
        mContext.unbindService(mServiceConnection);
    }

    @Test
    public void testEchoSmallPayload() throws Exception {
        doTestLEcho(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testEchoBigPayload() throws Exception {
        doTestLEcho(ARRAY_LENGTH_BIG);
    }

    @Test
    public void testEchoMultipleArgsSmallPayload() throws Exception {
        doTestMultipleArgs(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testEchoMultipleArgsBigPayload() throws Exception {
        doTestMultipleArgs(ARRAY_LENGTH_BIG);
    }

    @Test
    public void testNullParcelable() throws Exception {
        StableAIDLTestLargeParcelable r = mBinder.echo(null);

        assertThat(r).isNull();

        long argValue = 0x12345678;

        long rValue = mBinder.echoWithLong(null, argValue);

        assertThat(argValue).isEqualTo(rValue);
    }

    private void doTestLEcho(int payloadSize) throws Exception {
        StableAIDLTestLargeParcelable orig = new StableAIDLTestLargeParcelable();
        orig.payload = LargeParcelableTest.createByteArray(payloadSize);

        StableAIDLTestLargeParcelable r = mBinder.echo(orig);

        assertThat(r).isNotNull();
        assertThat(r.payload).isNotNull();
        assertThat(r.payload).isEqualTo(orig.payload);
        if (payloadSize > LargeParcelable.MAX_DIRECT_PAYLOAD_SIZE) {
            assertThat(orig.sharedMemoryFd).isNotNull();
            assertThat(r.sharedMemoryFd).isNotNull();
        } else {
            assertThat(orig.sharedMemoryFd).isNull();
            assertThat(r.sharedMemoryFd).isNull();
        }
    }

    private void doTestMultipleArgs(int payloadSize) throws Exception {
        StableAIDLTestLargeParcelable orig = new StableAIDLTestLargeParcelable();
        orig.payload = LargeParcelableTest.createByteArray(payloadSize);
        long argValue = 0x12345678;
        long expectedRet = argValue + IStableAIDLBinderTestService.calcByteSum(orig);

        long r = mBinder.echoWithLong(orig, argValue);

        assertThat(r).isEqualTo(expectedRet);
        if (payloadSize > LargeParcelable.MAX_DIRECT_PAYLOAD_SIZE) {
            assertThat(orig.sharedMemoryFd).isNotNull();
        } else {
            assertThat(orig.sharedMemoryFd).isNull();
        }
    }

    // This class shows how binder call is wrapped to make it more efficient with shared memory.
    // Most code is copied from auto-generated code with only small changes.
    private static final class IStableAIDLTestBinderWrapper {
        static final int TRANSACTION_echo = (IBinder.FIRST_CALL_TRANSACTION + 0);
        static final int TRANSACTION_echoWithLong = (IBinder.FIRST_CALL_TRANSACTION + 1);

        private final IStableAIDLTestBinder mBinder;

        IStableAIDLTestBinderWrapper(IStableAIDLTestBinder binder) {
            mBinder = binder;
        }

        StableAIDLTestLargeParcelable echo(StableAIDLTestLargeParcelable p) throws
                RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            StableAIDLTestLargeParcelable result;
            try {
                data.writeInterfaceToken(IStableAIDLTestBinder.DESCRIPTOR);
                if (p != null) {
                    data.writeInt(1);
                    /// changed from auto-generated code
                    LargeParcelable.serializeStableAIDLParcelable(data, p, 0, true);
                } else {
                    data.writeInt(0);
                }
                boolean status = mBinder.asBinder().transact(TRANSACTION_echo, data, reply, 0);
                if (!status) {
                    throw new IllegalArgumentException();
                }
                reply.readException();
                if (0 != reply.readInt()) {
                    // changed from auto-generated code
                    result = StableAIDLTestLargeParcelable.CREATOR.createFromParcel(reply);
                    result =
                            (StableAIDLTestLargeParcelable)
                                    LargeParcelable.reconstructStableAIDLParcelable(
                                    result, true);
                } else {
                    result = null;
                }
            } finally {
                reply.recycle();
                data.recycle();
            }
            return result;
        }

        public long echoWithLong(StableAIDLTestLargeParcelable p, long v) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            long result;
            try {
                data.writeInterfaceToken(IStableAIDLTestBinder.DESCRIPTOR);
                if (p != null) {
                    data.writeInt(1);
                    // changed from auto-generated code
                    LargeParcelable.serializeStableAIDLParcelable(data, p, 0, true);
                } else {
                    data.writeInt(0);
                }
                data.writeLong(v);
                boolean status = mBinder.asBinder().transact(TRANSACTION_echoWithLong, data, reply,
                        0);
                if (!status) {
                    throw new IllegalArgumentException();
                }
                reply.readException();
                result = reply.readLong();
            } finally {
                reply.recycle();
                data.recycle();
            }
            return result;
        }
    }

    private final class TestServiceConnection implements ServiceConnection {
        public final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = new IStableAIDLTestBinderWrapper(IStableAIDLTestBinder.Stub.asInterface(
                    service));
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }
}
