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

package com.android.car.scriptexecutor;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutor;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutorConstants;
import com.android.car.telemetry.scriptexecutorinterface.IScriptExecutorListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class ScriptExecutorTest {

    private IScriptExecutor mScriptExecutor;
    private ScriptExecutor mInstance;
    private Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();


    private static final class ScriptExecutorListener extends IScriptExecutorListener.Stub {
        public Bundle mSavedBundle;
        public int mErrorType;
        public String mMessage;
        public String mStackTrace;
        public final CountDownLatch mSuccessLatch = new CountDownLatch(1);
        public final CountDownLatch mErrorLatch = new CountDownLatch(1);

        @Override
        public void onScriptFinished(byte[] result) {
        }

        @Override
        public void onSuccess(Bundle stateToPersist) {
            mSavedBundle = stateToPersist;
            mSuccessLatch.countDown();
        }

        @Override
        public void onError(int errorType, String message, String stackTrace) {
            mErrorType = errorType;
            mMessage = message;
            mStackTrace = stackTrace;
            mErrorLatch.countDown();
        }
    }

    private final ScriptExecutorListener mFakeScriptExecutorListener =
            new ScriptExecutorListener();

    // TODO(b/189241508). Parsing of publishedData is not implemented yet.
    // Null is the only accepted input.
    private final Bundle mPublishedData = null;
    private final Bundle mSavedState = new Bundle();

    private static final String LUA_SCRIPT =
            "function hello(state)\n"
                    + "    print(\"Hello World\")\n"
                    + "end\n";

    private static final String LUA_METHOD = "hello";

    private final CountDownLatch mBindLatch = new CountDownLatch(1);

    private static final int BIND_SERVICE_TIMEOUT_SEC = 5;
    private static final int SCRIPT_SUCCESS_TIMEOUT_SEC = 10;
    private static final int SCRIPT_ERROR_TIMEOUT_SEC = 10;


    private final ServiceConnection mScriptExecutorConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    mScriptExecutor = IScriptExecutor.Stub.asInterface(service);
                    mBindLatch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName className) {
                    fail("Service unexpectedly disconnected");
                }
            };

    // Helper method to invoke the script and wait for it to complete and return the result.
    private void runScriptAndWaitForResult(String script, String function, Bundle previousState)
            throws RemoteException {
        mScriptExecutor.invokeScript(script, function, mPublishedData, previousState,
                mFakeScriptExecutorListener);
        try {
            if (!mFakeScriptExecutorListener.mSuccessLatch.await(SCRIPT_SUCCESS_TIMEOUT_SEC,
                    TimeUnit.SECONDS)) {
                fail("Failed to get on_success called by the script on time");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    private void runScriptAndWaitForError(String script, String function) throws RemoteException {
        mScriptExecutor.invokeScript(script, function, mPublishedData, new Bundle(),
                mFakeScriptExecutorListener);
        try {
            if (!mFakeScriptExecutorListener.mErrorLatch.await(SCRIPT_ERROR_TIMEOUT_SEC,
                    TimeUnit.SECONDS)) {
                fail("Failed to get on_error called by the script on time");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Before
    public void setUp() throws InterruptedException {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.car.scriptexecutor",
                "com.android.car.scriptexecutor.ScriptExecutor"));
        mContext.bindServiceAsUser(intent, mScriptExecutorConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
        if (!mBindLatch.await(BIND_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Failed to bind to ScriptExecutor service");
        }
    }

    @Test
    public void invokeScript_helloWorld() throws RemoteException {
        // Expect to load "hello world" script successfully and push the function.
        mScriptExecutor.invokeScript(LUA_SCRIPT, LUA_METHOD, mPublishedData, mSavedState,
                mFakeScriptExecutorListener);
        // Sleep, otherwise the test case will complete before the script loads
        // because invokeScript is non-blocking.
        try {
            // TODO(b/192285332): Replace sleep logic with waiting for specific callbacks
            // to be called once they are implemented. Otherwise, this could be a flaky test.
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void invokeScript_returnsResult() throws RemoteException {
        String returnResultScript =
                "function hello(state)\n"
                        + "    result = {hello=\"world\"}\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResult(returnResultScript, "hello", mSavedState);

        // Expect to get back a bundle with a single string key: string value pair:
        // {"hello": "world"}
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("hello")).isEqualTo("world");
    }

    @Test
    public void invokeScript_allSupportedTypes() throws RemoteException {
        String script =
                "function knows(state)\n"
                        + "    result = {string=\"hello\", boolean=true, integer=1, number=1.1}\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResult(script, "knows", mSavedState);

        // Expect to get back a bundle with 4 keys, each corresponding to a distinct supported type.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("string")).isEqualTo("hello");
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("integer")).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getDouble("number")).isEqualTo(1.1);
    }

    @Test
    public void invokeScript_skipsUnsupportedTypes() throws RemoteException {
        String script =
                "function nested(state)\n"
                        + "    result = {string=\"hello\", boolean=true, integer=1, number=1.1}\n"
                        + "    result.nested_table = {x=0, y=0}\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResult(script, "nested", mSavedState);

        // Bundle does not contain any value under "nested_table" key, because nested tables are
        // not supported yet.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("nested_table")).isNull();
    }

    @Test
    public void invokeScript_emptyBundle() throws RemoteException {
        String script =
                "function empty(state)\n"
                        + "    result = {}\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResult(script, "empty", mSavedState);

        // If a script returns empty table as the result, we get an empty bundle.
        assertThat(mFakeScriptExecutorListener.mSavedBundle).isNotNull();
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(0);
    }

    @Test
    public void invokeScript_processPreviousStateAndReturnResult() throws RemoteException {
        // Here we verify that the script actually processes provided state from a previous run
        // and makes calculation based on that and returns the result.
        // TODO(b/189241508): update function signatures.
        String script =
                "function update(state)\n"
                        + "    result = {y = state.x+1}\n"
                        + "    on_success(result)\n"
                        + "end\n";
        Bundle previousState = new Bundle();
        previousState.putInt("x", 1);


        runScriptAndWaitForResult(script, "update", previousState);

        // Verify that y = 2, because y = x + 1 and x = 1.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("y")).isEqualTo(2);
    }

    @Test
    public void invokeScript_allSupportedTypesWorkRoundTripWithKeyNamesPreserved()
            throws RemoteException {
        // Here we verify that all supported types in supplied previous state Bundle are interpreted
        // by the script as expected.
        // TODO(b/189241508): update function signatures.
        String script =
                "function update_all(state)\n"
                        + "    result = {}\n"
                        + "    result.integer = state.integer + 1\n"
                        + "    result.number = state.number + 0.1\n"
                        + "    result.boolean = not state.boolean\n"
                        + "    result.string = state.string .. \"CADABRA\"\n"
                        + "    on_success(result)\n"
                        + "end\n";
        Bundle previousState = new Bundle();
        previousState.putInt("integer", 1);
        previousState.putDouble("number", 0.1);
        previousState.putBoolean("boolean", false);
        previousState.putString("string", "ABRA");


        runScriptAndWaitForResult(script, "update_all", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("integer")).isEqualTo(2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getDouble("number")).isEqualTo(0.2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("string")).isEqualTo(
                "ABRACADABRA");
    }

    @Test
    public void invokeScript_scriptCallsOnError() throws RemoteException {
        String script =
                "function calls_on_error()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(\"one is not equal to two\")\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "calls_on_error");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo("one is not equal to two");
    }

    @Test
    public void invokeScript_tooManyParametersInOnError() throws RemoteException {
        String script =
                "function too_many_params_in_on_error()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(\"param1\", \"param2\")\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "too_many_params_in_on_error");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_error can push only a single string parameter from Lua");
    }

    @Test
    public void invokeScript_onErrorOnlyAcceptsString() throws RemoteException {
        String script =
                "function only_string()\n"
                        + "    if 1 ~= 2 then\n"
                        + "        on_error(false)\n"
                        + "        return\n"
                        + "    end\n"
                        + "end\n";

        runScriptAndWaitForError(script, "only_string");

        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_error can push only a single string parameter from Lua");
    }
}

