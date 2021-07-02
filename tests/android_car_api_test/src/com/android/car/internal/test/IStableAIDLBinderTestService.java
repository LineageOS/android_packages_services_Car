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

import android.app.Service;
import android.car.apitest.IStableAIDLTestBinder;
import android.car.apitest.StableAIDLTestLargeParcelable;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.internal.LargeParcelable;

public final class IStableAIDLBinderTestService extends Service {
    private static final String TAG = IStableAIDLBinderTestService.class.getSimpleName();

    private final IStableAIDLBinderTestImpl mBinder = new IStableAIDLBinderTestImpl();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // This class shows how binder call is wrapped to make it more efficient with shared memory.
    // Most code is copied from auto-generated code with only small changes.
    private static final class IStableAIDLBinderTestImpl extends IStableAIDLTestBinder.Stub {
        // copied due to package scope.
        static final int TRANSACTION_echo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);

        // Override some of auto-generated code to make efficient transfer
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            Log.i(TAG, "onTransact:" + code);
            String descriptor = IStableAIDLTestBinder.DESCRIPTOR;
            switch (code) {
                case TRANSACTION_echo: {
                    data.enforceInterface(descriptor);
                    StableAIDLTestLargeParcelable arg0;
                    if (0 != data.readInt()) {
                        Log.i(TAG, "echo has non-null arg0");
                        arg0 = StableAIDLTestLargeParcelable.CREATOR.createFromParcel(data);
                    } else {
                        arg0 = null;
                        Log.i(TAG, "echo null input");
                    }
                    if (arg0 != null) {
                        if (arg0.payload != null) {
                            Log.i(TAG, "echo payload length:" + arg0.payload.length);
                        }
                        if (arg0.sharedMemoryFd != null) {
                            Log.i(TAG, "echo has shared memory");
                        }
                    }
                    StableAIDLTestLargeParcelable result = this.echo(arg0);
                    if (result != null && result.payload != null) {
                        Log.i(TAG, "echo reply payload:" + result.payload.length);
                    }
                    reply.writeNoException();
                    if (result != null) {
                        reply.writeInt(1);
                        // changed from auto-generated code
                        LargeParcelable.serializeStableAIDLParcelable(reply,
                                result, Parcelable.PARCELABLE_WRITE_RETURN_VALUE, false);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
                default: {
                    return super.onTransact(code, data, reply, flags);
                }
            }
        }

        @Override
        public StableAIDLTestLargeParcelable echo(StableAIDLTestLargeParcelable p) {
            // Keep shared memory for the returned one as it will be sent back.
            return (StableAIDLTestLargeParcelable) LargeParcelable.reconstructStableAIDLParcelable(
                            p, true);
        }

        @Override
        public long echoWithLong(StableAIDLTestLargeParcelable p, long v) {
            StableAIDLTestLargeParcelable r =
                    (StableAIDLTestLargeParcelable) LargeParcelable.reconstructStableAIDLParcelable(
                            p, false);
            return calcByteSum(r) + v;
        }
    }

    public static long calcByteSum(StableAIDLTestLargeParcelable p) {
        long ret = 0;
        if (p != null && p.payload != null) {
            for (int i = 0; i < p.payload.length; i++) {
                ret = ret + p.payload[i];
            }
        }
        return ret;
    }
}
