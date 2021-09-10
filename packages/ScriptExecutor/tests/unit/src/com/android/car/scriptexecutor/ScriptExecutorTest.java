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
import android.os.IBinder;
import android.os.PersistableBundle;
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
        public PersistableBundle mSavedBundle;
        public PersistableBundle mFinalResult;
        public int mErrorType;
        public String mMessage;
        public String mStackTrace;
        public final CountDownLatch mResponseLatch = new CountDownLatch(1);

        @Override
        public void onScriptFinished(PersistableBundle result) {
            mFinalResult = result;
            mResponseLatch.countDown();
        }

        @Override
        public void onSuccess(PersistableBundle stateToPersist) {
            mSavedBundle = stateToPersist;
            mResponseLatch.countDown();
        }

        @Override
        public void onError(int errorType, String message, String stackTrace) {
            mErrorType = errorType;
            mMessage = message;
            mStackTrace = stackTrace;
            mResponseLatch.countDown();
        }
    }

    private final ScriptExecutorListener mFakeScriptExecutorListener =
            new ScriptExecutorListener();

    // TODO(b/189241508). Parsing of publishedData is not implemented yet.
    // Null is the only accepted input.
    private final PersistableBundle mPublishedData = null;
    private final PersistableBundle mSavedState = new PersistableBundle();

    private static final String LUA_SCRIPT =
            "function hello(state)\n"
                    + "    print(\"Hello World\")\n"
                    + "end\n";

    private static final String LUA_METHOD = "hello";

    private final CountDownLatch mBindLatch = new CountDownLatch(1);

    private static final int BIND_SERVICE_TIMEOUT_SEC = 5;
    private static final int SCRIPT_PROCESSING_TIMEOUT_SEC = 10;


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

    // Helper method to invoke the script and wait for it to complete and return a response.
    private void runScriptAndWaitForResponse(String script, String function,
            PersistableBundle previousState)
            throws RemoteException {
        mScriptExecutor.invokeScript(script, function, mPublishedData, previousState,
                mFakeScriptExecutorListener);
        try {
            if (!mFakeScriptExecutorListener.mResponseLatch.await(SCRIPT_PROCESSING_TIMEOUT_SEC,
                    TimeUnit.SECONDS)) {
                fail("Failed to get the callback method called by the script on time");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }


    private void runScriptAndWaitForError(String script, String function) throws RemoteException {
        runScriptAndWaitForResponse(script, function, new PersistableBundle());
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


        runScriptAndWaitForResponse(returnResultScript, "hello", mSavedState);

        // Expect to get back a bundle with a single string key: string value pair:
        // {"hello": "world"}
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("hello")).isEqualTo("world");
    }

    @Test
    public void invokeScript_allSupportedPrimitiveTypes() throws RemoteException {
        String script =
                "function knows(state)\n"
                        + "    result = {string=\"hello\", boolean=true, integer=1, number=1.1}\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "knows", mSavedState);

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


        runScriptAndWaitForResponse(script, "nested", mSavedState);

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


        runScriptAndWaitForResponse(script, "empty", mSavedState);

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
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("x", 1);


        runScriptAndWaitForResponse(script, "update", previousState);

        // Verify that y = 2, because y = x + 1 and x = 1.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("y")).isEqualTo(2);
    }

    @Test
    public void invokeScript_allSupportedPrimitiveTypesWorkRoundTripWithKeyNamesPreserved()
            throws RemoteException {
        // Here we verify that all supported primitive types in supplied previous state Bundle
        // are interpreted by the script as expected.
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
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("integer", 1);
        previousState.putDouble("number", 0.1);
        previousState.putBoolean("boolean", false);
        previousState.putString("string", "ABRA");


        runScriptAndWaitForResponse(script, "update_all", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("integer")).isEqualTo(2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getDouble("number")).isEqualTo(0.2);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getString("string")).isEqualTo(
                "ABRACADABRA");
    }

    @Test
    public void invokeScript_allSupportedArrayTypesWorkRoundTripWithKeyNamesPreserved()
            throws RemoteException {
        // Here we verify that all supported array types in supplied previous state Bundle are
        // interpreted by the script as expected.
        // TODO(b/189241508): update function signatures.
        String script =
                "function arrays(state)\n"
                        + "    result = {}\n"
                        + "    result.int_array = state.int_array\n"
                        + "    result.long_array = state.long_array\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        int[] int_array = new int[]{1, 2};
        long[] int_array_in_long = new long[]{1, 2};
        long[] long_array = new long[]{1, 2, 3};
        previousState.putIntArray("int_array", int_array);
        previousState.putLongArray("long_array", long_array);


        runScriptAndWaitForResponse(script, "arrays", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(2);
        // Lua has only one lua_Integer. Here Java long is used to represent it when data is
        // transferred from Lua to CarTelemetryService.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("int_array")).isEqualTo(
                int_array_in_long);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("long_array")).isEqualTo(
                long_array);
    }

    @Test
    public void invokeScript_modifiesArray()
            throws RemoteException {
        // Verify that an array modified by a script is properly sent back by the callback.
        // TODO(b/189241508): update function signatures.
        String script =
                "function modify_array(state)\n"
                        + "    result = {}\n"
                        + "    result.long_array = state.long_array\n"
                        + "    result.long_array[2] = 100\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);
        long[] expected_array = new long[]{1, 100, 3};


        runScriptAndWaitForResponse(script, "modify_array", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("long_array")).isEqualTo(
                expected_array);
    }

    @Test
    public void invokeScript_arraysWithLengthAboveLimitCauseError()
            throws RemoteException {
        // Verifies that arrays pushed by Lua that have their size over the limit cause error.
        // TODO(b/189241508): update function signatures.
        String script =
                "function size_limit(state)\n"
                        + "    result = {}\n"
                        + "    result.huge_array = {}\n"
                        + "    for i=1, 10000 do\n"
                        + "        result.huge_array[i]=i\n"
                        + "    end\n"
                        + "    on_success(result)\n"
                        + "end\n";

        runScriptAndWaitForResponse(script, "size_limit", mSavedState);

        // Verify that expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "Returned table huge_array exceeds maximum allowed size of 1000 "
                        + "elements. This key-value cannot be unpacked successfully. This error "
                        + "is unrecoverable.");
    }

    @Test
    public void invokeScript_arrayContainingVaryingTypesCausesError()
            throws RemoteException {
        // Verifies that values in returned array must be the same integer type.
        // For example string values in a Lua array are not allowed.
        // TODO(b/189241508): update function signatures.
        String script =
                "function table_with_numbers_and_strings(state)\n"
                        + "    result = {}\n"
                        + "    result.mixed_array = state.long_array\n"
                        + "    result.mixed_array[2] = 'a'\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);

        runScriptAndWaitForResponse(script, "table_with_numbers_and_strings", previousState);

        // Verify that expected error is received.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "Returned table mixed_array contains values of types other than expected integer."
                        + " This key-value cannot be unpacked successfully. This error is "
                        + "unrecoverable.");
    }

    @Test
    public void invokeScript_InTablesWithBothKeysAndIndicesCopiesOnlyIndexedData()
            throws RemoteException {
        // Documents the current behavior that copies only indexed values in a Lua table that
        // contains both keyed and indexed data.
        // TODO(b/189241508): update function signatures.
        String script =
                "function keys_and_indices(state)\n"
                        + "    result = {}\n"
                        + "    result.mixed_array = state.long_array\n"
                        + "    result.mixed_array['a'] = 130\n"
                        + "    on_success(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        long[] long_array = new long[]{1, 2, 3};
        previousState.putLongArray("long_array", long_array);

        runScriptAndWaitForResponse(script, "keys_and_indices", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getLongArray("mixed_array")).isEqualTo(
                long_array);
    }

    @Test
    public void invokeScript_noLuaBufferOverflowForLargeInputArrays() throws RemoteException {
        // Tests that arrays with length that exceed internal Lua buffer size of 20 elements
        // do not cause buffer overflow and are handled properly.
        // TODO(b/189241508): update function signatures.
        String script =
                "function large_input_array(state)\n"
                        + "    sum = 0\n"
                        + "    for _, val in ipairs(state.long_array) do\n"
                        + "        sum = sum + val\n"
                        + "    end\n"
                        + "    result = {total = sum}\n"
                        + "    on_success(result)\n"
                        + "end\n";

        PersistableBundle previousState = new PersistableBundle();
        int n = 10000;
        long[] longArray = new long[n];
        for (int i = 0; i < n; i++) {
            longArray[i] = i;
        }
        previousState.putLongArray("long_array", longArray);
        long expected_sum =
                (longArray[0] + longArray[n - 1]) * n / 2; // sum of an arithmetic sequence.

        runScriptAndWaitForResponse(script, "large_input_array", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mSavedBundle.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mSavedBundle.getInt("total")).isEqualTo(
                expected_sum);
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

    @Test
    public void invokeScript_returnsFinalResult() throws RemoteException {
        String returnFinalResultScript =
                "function script_finishes(state)\n"
                        + "    result = {data = state.input + 1}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("input", 1);

        runScriptAndWaitForResponse(returnFinalResultScript, "script_finishes", previousState);

        // Expect to get back a bundle with a single key-value pair {"data": 2}
        // because data = state.input + 1 as in the script body above.
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(1);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getInt("data")).isEqualTo(2);
    }

    @Test
    public void invokeScript_allPrimitiveSupportedTypesForReturningFinalResult()
            throws RemoteException {
        // Here we verify that all supported primitive types are present in the returned final
        // result bundle are present.
        // TODO(b/189241508): update function signatures.
        String script =
                "function finalize_all(state)\n"
                        + "    result = {}\n"
                        + "    result.integer = state.integer + 1\n"
                        + "    result.number = state.number + 0.1\n"
                        + "    result.boolean = not state.boolean\n"
                        + "    result.string = state.string .. \"CADABRA\"\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";
        PersistableBundle previousState = new PersistableBundle();
        previousState.putInt("integer", 1);
        previousState.putDouble("number", 0.1);
        previousState.putBoolean("boolean", false);
        previousState.putString("string", "ABRA");


        runScriptAndWaitForResponse(script, "finalize_all", previousState);

        // Verify that keys are preserved but the values are modified as expected.
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(4);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getInt("integer")).isEqualTo(2);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getDouble("number")).isEqualTo(0.2);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getBoolean("boolean")).isEqualTo(true);
        assertThat(mFakeScriptExecutorListener.mFinalResult.getString("string")).isEqualTo(
                "ABRACADABRA");
    }

    @Test
    public void invokeScript_emptyFinalResultBundle() throws RemoteException {
        String script =
                "function empty_final_result(state)\n"
                        + "    result = {}\n"
                        + "    on_script_finished(result)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "empty_final_result", mSavedState);

        // If a script returns empty table as the final result, we get an empty bundle.
        assertThat(mFakeScriptExecutorListener.mFinalResult).isNotNull();
        assertThat(mFakeScriptExecutorListener.mFinalResult.size()).isEqualTo(0);
    }

    @Test
    public void invokeScript_wrongNumberOfCallbackInputsInOnScriptFinished()
            throws RemoteException {
        String script =
                "function wrong_number_of_outputs_in_on_script_finished(state)\n"
                        + "    result = {}\n"
                        + "    extra = 1\n"
                        + "    on_script_finished(result, extra)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "wrong_number_of_outputs_in_on_script_finished",
                mSavedState);

        // We expect to get an error here because we expect only 1 input parameter in
        // on_script_finished.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_script_finished can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongNumberOfCallbackInputsInOnSuccess() throws RemoteException {
        String script =
                "function wrong_number_of_outputs_in_on_success(state)\n"
                        + "    result = {}\n"
                        + "    extra = 1\n"
                        + "    on_success(result, extra)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "wrong_number_of_outputs_in_on_success", mSavedState);

        // We expect to get an error here because we expect only 1 input parameter in on_success.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongTypeInOnSuccess() throws RemoteException {
        String script =
                "function wrong_type_in_on_success(state)\n"
                        + "    result = 1\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "wrong_type_in_on_success", mSavedState);

        // We expect to get an error here because the type of the input parameter for on_success
        // must be a Lua table.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }

    @Test
    public void invokeScript_wrongTypeInOnScriptFinished() throws RemoteException {
        String script =
                "function wrong_type_in_on_script_finished(state)\n"
                        + "    result = 1\n"
                        + "    on_success(result)\n"
                        + "end\n";


        runScriptAndWaitForResponse(script, "wrong_type_in_on_script_finished", mSavedState);

        // We expect to get an error here because the type of the input parameter for
        // on_script_finished must be a Lua table.
        assertThat(mFakeScriptExecutorListener.mErrorType).isEqualTo(
                IScriptExecutorConstants.ERROR_TYPE_LUA_SCRIPT_ERROR);
        assertThat(mFakeScriptExecutorListener.mMessage).isEqualTo(
                "on_success can push only a single parameter from Lua - a Lua table");
    }
}

