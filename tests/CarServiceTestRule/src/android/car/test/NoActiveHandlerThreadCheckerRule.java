/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.car.test;

import static com.google.common.truth.Truth.assertWithMessage;

import android.util.Log;

import com.android.car.CarServiceUtils;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.List;

public class NoActiveHandlerThreadCheckerRule implements TestRule {

    private static final String TAG = "NAHTCheckRule";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                // Quit all handler threads created by other test modules, otherwise, they may
                // cause this test case to fail.
                CarServiceUtils.quitHandlerThreads();

                try {
                    // Run the actual test
                    base.evaluate();

                    // After each test
                    if (DBG) {
                        Log.d(TAG, "running NoActiveHandlerThreadCheckerRule rule after "
                                + description.getDisplayName());
                    }

                    // Do not verify this if the test fails.
                    verifyNoActiveHandlerThreads();
                } finally {
                    // If the test fails or verifyNoActiveHandlerThreads fails, we should still
                    // quit all handler threads to prevent affecting other test cases.
                    CarServiceUtils.quitHandlerThreads();
                }
            }
        };
    }

    private void verifyNoActiveHandlerThreads() {
        List<String> activeThreadNames = CarServiceUtils.getActiveHandlerThreadNames();
        assertWithMessage("Handler threads: " + activeThreadNames
                + " are still active after the test case, do you forgot to call "
                + "releaseHandlerThread?")
                .that(activeThreadNames).isEmpty();
    }
}
