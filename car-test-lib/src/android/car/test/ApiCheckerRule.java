/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule used to validate Car API requirements on CTS tests.
 */
// TODO(b/242315785): document everything - including mainline versioning and include examples
public final class ApiCheckerRule implements TestRule {

    // TODO(b/242315785): add missing features
    // - CDD support (and its own ApiRequirements)
    // - check for car / platform version
    // - new annotation for exception cases, like:
    //   - not validating @ApiTest content
    //   - assert throw exception on unsupported version
    //   - ignore on unsupported version

    private static final String TAG = ApiCheckerRule.class.getSimpleName();

    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final boolean mEnforceTestApiAnnotations;

    /**
     * Builder.
     */
    public static final class Builder {
        private boolean mEnforceTestApiAnnotations = true;

        /**
         * Creates a new rule.
         */
        public ApiCheckerRule build() {
            return new ApiCheckerRule(this);
        }

        /**
         * Don't fail the test if the required annotations (like {@link ApiTest}) are missing.
         */
        public Builder disableAnnotationsCheck() {
            mEnforceTestApiAnnotations = false;
            return this;
        }
    }

    private ApiCheckerRule(Builder builder) {
        mEnforceTestApiAnnotations = builder.mEnforceTestApiAnnotations;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (DBG) {
                    Log.d(TAG, "evaluating " + description.getDisplayName());
                }

                ApiTest apiTest = null;
                for (Annotation annotation : description.getAnnotations()) {
                    if (annotation instanceof ApiTest) {
                        apiTest = (ApiTest) annotation;
                        break;
                    }
                }

                if (DBG) {
                    Log.d(TAG, "ApiTest: " + apiTest);
                }

                // First check for @ApiTest annotation
                if (apiTest == null) {
                    if (mEnforceTestApiAnnotations) {
                        throw new IllegalStateException("Test is missing @ApiTest annotation");
                    } else {
                        Log.w(TAG, "Test " + description + " doesn't have required annotations, "
                                + "but rule is not enforcing it");
                    }
                } else {
                    // Then validate it
                    String[] apis = apiTest.apis();
                    if (apis == null || apis.length == 0) {
                        throw new IllegalStateException("empty @ApiTest annotation");
                    }
                    List<String> invalidApis = new ArrayList<>();
                    for (String api: apis) {
                        Member member = ApiHelper.resolve(api);
                        if (member == null) {
                            invalidApis.add(api);
                        }
                    }
                    if (!invalidApis.isEmpty()) {
                        throw new IllegalStateException("Could not resolve some APIs ("
                                + invalidApis + ") on annotation (" + apiTest + ")");
                    }
                }

                base.evaluate();
            }

        };
    }
}
