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

import android.car.extendedapitest.testbase.CarLessApiTestBase;
import android.car.feature.Flags;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;

import com.android.car.internal.LargeParcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Integration test for {@link com.android.car.internal.LargeParcelable}.
 *
 * <p>This test uses the {@link TestLargeParcelable} class which is a subclass for
 * {@link LargeParcelableBase} to communicate large data between the test process and a
 * {@link LargeParcelableTestService} we created.
 *
 * <p>This test verifies that we can send and receive large parcelable to/from a service.
 *
 * <p>The {@link TestParcelable} and {@link TestLargeParcelable} are not stable parcelable. For
 * stable parcelable usage, see {@link LargeParcelableJavaStableAIDLTest}.
 */
@RunWith(ParameterizedAndroidJunit4.class)
@SmallTest
public final class LargeParcelableTest extends CarLessApiTestBase {

    private static final String TAG = LargeParcelableTest.class.getSimpleName();

    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final int ARRAY_LENGTH_SMALL = 2048;
    // The current threshold is 4096.
    private static final int ARRAY_LENGTH_BIG = 4099;

    private final TestServiceConnection mServiceConnection = new TestServiceConnection();

    private IJavaTestBinder mBinder;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_LARGEPARCELABLE_USE_NATIVE_PARCEL);
    }

    @Rule
    public SetFlagsRule mSetFlagsRule;

    public LargeParcelableTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws Exception {
        LargeParcelable.setClassLoader(mContext.getClassLoader());
        Intent intent = new Intent();
        intent.setClassName(mContext, LargeParcelableTestService.class.getName());
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);

        JavaMockitoHelper.await(mServiceConnection.latch, DEFAULT_TIMEOUT_MS);
    }

    @After
    public void tearDown() {
        mContext.unbindService(mServiceConnection);
    }

    @Test
    public void testLocalSerializationDeserializationSmallPayload() throws Exception {
        doTestLocalSerializationDeserialization(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testLocalSerializationDeserializationBigPayload() throws Exception {
        doTestLocalSerializationDeserialization(ARRAY_LENGTH_BIG);
    }

    @Test
    public void testLocalSerializationDeserializationNullPayload() throws Exception {
        try (TestLargeParcelable origParcelable = new TestLargeParcelable()) {
            Parcel dest = Parcel.obtain();
            origParcelable.writeToParcel(dest, 0);
            dest.setDataPosition(0);

            try (TestLargeParcelable newParcelable = new TestLargeParcelable(dest)) {
                assertThat(newParcelable.byteData).isNull();
            }
        }
    }

    @Test
    public void testRemoteNullPayload() throws Exception {
        try (TestLargeParcelable origParcelable = new TestLargeParcelable()) {
            try (TestLargeParcelable r = mBinder.echoTestLargeParcelable(origParcelable)) {
                assertThat(r).isNotNull();
                assertThat(r.byteData).isNull();
            }
        }
    }

    @Test
    public void testTestParcelableSmallPayload() throws Exception {
        doTestLargeParcelable(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testTestParcelableBigPayload() throws Exception {
        doTestLargeParcelable(ARRAY_LENGTH_BIG);
    }

    @Test
    public void testLargeParcelableSmallPayload() throws Exception {
        doTestTestLargeParcelable(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testLargeParcelableBigPayload() throws Exception {
        doTestTestLargeParcelable(ARRAY_LENGTH_BIG);
    }

    @Test
    public void testMultiArgsWithNullPayload() throws Exception {
        long argValue = 0x12345678;

        try (TestLargeParcelable origParcelable = new TestLargeParcelable()) {
            long r = mBinder.echoLongWithTestLargeParcelable(origParcelable, argValue);

            assertThat(r).isEqualTo(argValue);
        }
    }

    @Test
    public void testMultiArgsSmallPayload() throws Exception {
        doTestMultipleArgs(ARRAY_LENGTH_SMALL);
    }

    @Test
    public void testMultiArgsBigPayload() throws Exception {
        doTestMultipleArgs(ARRAY_LENGTH_BIG);
    }

    // Test that after closing the LargeParcelableBase, the shared memory file must be released and
    // we will not leak shared memory file descriptor. This test is slow because of the loops.
    @LargeTest
    @Test
    public void testClosingLargeParcelableBase_releaseResource() throws Exception {
        byte[] origArray = createByteArray(ARRAY_LENGTH_BIG);
        // Loop for a 32k times so that if we don't clean up fd, we will hit fd limit. In Android
        // the soft limit for nofiles is 32k.
        int loopCount = 32 * 1024 + 1;

        TestLargeParcelable[] parcelables = new TestLargeParcelable[loopCount];
        for (int i = 0; i < loopCount; i++) {
            // We share the same byte array, so this will not allocate many memory.
            parcelables[i] = new TestLargeParcelable(origArray);
        }

        for (int i = 0; i < loopCount; i++) {
            try (TestLargeParcelable echoed = mBinder.echoTestLargeParcelable(parcelables[i])) {
                // Do nothing.
            }
            // After close, the shared memory allocated should be closed, so we will not keep
            // increasing memory size.
            parcelables[i].close();
        }
    }

    private void doTestLargeParcelable(int payloadSize) throws Exception {
        byte[] origArray = createByteArray(payloadSize);

        TestParcelable origParcelable = new TestParcelable(origArray);
        try (var sendParcelable = new LargeParcelable(origParcelable)) {
            try (LargeParcelable r = mBinder.echoLargeParcelable(sendParcelable)) {
                assertThat(r).isNotNull();

                TestParcelable receivedParcelable = (TestParcelable) r.getParcelable();

                assertThat(receivedParcelable).isNotNull();
                assertThat(receivedParcelable.byteData).isEqualTo(origArray);
            }
        }
    }

    private void doTestTestLargeParcelable(int payloadSize) throws Exception {
        byte[] origArray = createByteArray(payloadSize);

        try (var origParcelable = new TestLargeParcelable(origArray)) {
            try (TestLargeParcelable r = mBinder.echoTestLargeParcelable(origParcelable)) {
                assertThat(r).isNotNull();
                assertThat(r.byteData).isNotNull();
                assertThat(r.byteData).isEqualTo(origArray);
            }
        }
    }

    private void doTestLocalSerializationDeserialization(int payloadSize) throws Exception {
        byte[] origArray = createByteArray(payloadSize);
        try (TestLargeParcelable origParcelable = new TestLargeParcelable(origArray)) {
            Parcel dest = Parcel.obtain();

            origParcelable.writeToParcel(dest, 0);
            dest.setDataPosition(0);

            try (TestLargeParcelable newParcelable = new TestLargeParcelable(dest)) {
                assertThat(newParcelable.byteData).isNotNull();
                assertThat(newParcelable.byteData).isEqualTo(origArray);
            }
        }
    }

    private void doTestMultipleArgs(int payloadSize) throws Exception {
        byte[] origArray = createByteArray(payloadSize);

        try (TestLargeParcelable origParcelable = new TestLargeParcelable(origArray)) {
            long argValue = 0x12345678;
            long expectedRet = argValue + LargeParcelableTestService.calcByteSum(origParcelable);

            long r = mBinder.echoLongWithTestLargeParcelable(origParcelable, argValue);

            assertThat(r).isEqualTo(expectedRet);
        }
    }

    /**
     * Creates a byte array of the specified length, populated with incrementing byte values.
     *
     * @param length The desired length of the byte array.
     * @return A byte array of the specified length, populated with incrementing byte values.
     */
    public static byte[] createByteArray(int length) {
        byte[] array = new byte[length];
        byte val = 0x7f;
        for (int i = 0; i < length; i++) {
            array[i] = val;
            val++;
        }
        return array;
    }

    private final class TestServiceConnection implements ServiceConnection {
        public final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IJavaTestBinder.Stub.asInterface(service);
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    }
}
