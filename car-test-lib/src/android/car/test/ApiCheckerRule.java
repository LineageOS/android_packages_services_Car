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

import android.car.Car;
import android.car.CarVersion;
import android.car.PlatformVersion;
import android.car.PlatformVersionMismatchException;
import android.car.annotation.ApiRequirements;
import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule used to validate Car API requirements on CTS tests.
 */
// TODO(b/242315785): document everything - including mainline versioning and include examples
public final class ApiCheckerRule implements TestRule {

    // TODO(b/242315785): add missing features
    // - CDD support (and its own ApiRequirements)
    // - new annotation for exception cases, like:
    //   - not validating @ApiTest content
    //   - assert throw exception on unsupported version
    //   - ignore on unsupported version

    public static final String TAG = ApiCheckerRule.class.getSimpleName();

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

    /**
     * Checks whether the test is running in an environment that supports the given API.
     *
     * @param api API as defined by {@link ApiTest}.
     * @return whether the test is running in an environment that supports the
     * {@link ApiRequirements} defined in such API.
     */
    public boolean isApiSupported(String api) {
        ApiRequirements apiRequirements = getApiRequirements(api);

        if (apiRequirements == null) {
            throw new IllegalStateException("No @ApiRequirements on " + api);
        }

        return isSupported(apiRequirements);
    }

    private boolean isSupported(ApiRequirements apiRequirements) {
        PlatformVersion platformVersion = Car.getPlatformVersion();
        boolean isSupported = platformVersion
                .isAtLeast(apiRequirements.minPlatformVersion().get());
        if (DBG) {
            Log.d(TAG, "isSupported(" + apiRequirements + "): platformVersion=" + platformVersion
                    + ",supported=" + isSupported);
        }
        return isSupported;
    }

    private static ApiRequirements getApiRequirements(String api) {
        Member member = ApiHelper.resolve(api);
        if (member == null) {
            throw new IllegalArgumentException("API not found: " + api);
        }
        return getApiRequirements(member);
    }

    private static ApiRequirements getApiRequirements(Member member) {
        if (member instanceof Field) {
            return ((Field) member).getAnnotation(ApiRequirements.class);
        }
        if (member instanceof Method) {
            return ((Method) member).getAnnotation(ApiRequirements.class);
        }
        throw new UnsupportedOperationException("Invalid member type for API: " + member);
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

                // Variables below are used to validate that all ApiRequirements are compatible
                List<String> allApis = new ArrayList<>();
                List<ApiRequirements> allApiRequirements = new ArrayList<>();
                boolean compatibleApis = true;
                ApiRequirements firstApiRequirements = null;

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
                        allApis.add(api);
                        Member member = ApiHelper.resolve(api);
                        if (member == null) {
                            invalidApis.add(api);
                            continue;
                        }
                        ApiRequirements apiRequirements = getApiRequirements(member);
                        allApiRequirements.add(apiRequirements);
                        if (firstApiRequirements == null) {
                            firstApiRequirements = apiRequirements;
                            continue;
                        }
                        // Make sure all ApiRequirements are compatible
                        if (!apiRequirements.minCarVersion()
                                .equals(firstApiRequirements.minCarVersion())
                                || !apiRequirements.minPlatformVersion()
                                        .equals(firstApiRequirements.minPlatformVersion())) {
                            Log.w(TAG, "Found incompatible API requirement (" + apiRequirements
                                    + ") on " + api + "(first ApiRequirements is "
                                    + firstApiRequirements + ")");
                            compatibleApis = false;
                        } else {
                            Log.d(TAG, "Multiple @ApiRequirements found but they're compatible");
                        }
                    }
                    if (!invalidApis.isEmpty()) {
                        throw new IllegalStateException("Could not resolve some APIs ("
                                + invalidApis + ") on annotation (" + apiTest + ")");
                    }

                }

                if (firstApiRequirements == null) {
                    if (mEnforceTestApiAnnotations) {
                        throw new IllegalStateException("Missing @ApiRequirements");
                    } else {
                        Log.w(TAG, "Test " + description + " doesn't have required "
                                + "@ApiRequirements, but rule is not enforcing it");
                    }
                    base.evaluate();
                    return;
                }

                if (!compatibleApis) {
                    throw new IncompatibleApiRequirementsException(allApis, allApiRequirements);
                }

                // Finally, run the test and assert results depending on whether it's supported or
                // not
                if (isSupported(firstApiRequirements)) {
                    if (DBG) {
                        Log.d(TAG, "Car / Platform combo is supported, running "
                                + description.getDisplayName());
                    }
                    base.evaluate();
                } else {
                    Log.i(TAG, "Car / Platform combo is NOT supported, running "
                            + description.getDisplayName() + " but expecting "
                                    + "PlatformVersionMismatchException");
                    try {
                        base.evaluate();
                        throw new PlatformVersionMismatchExceptionNotThrownException(
                                Car.getCarVersion(), Car.getPlatformVersion(),
                                firstApiRequirements);
                    } catch (PlatformVersionMismatchException e) {
                        if (DBG) {
                            Log.d(TAG, "Exception thrown as expected: " + e);
                        }
                    }
                }
            }
        };
    } // apply

    public static final class PlatformVersionMismatchExceptionNotThrownException
            extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final CarVersion mCarVersion;
        private final PlatformVersion mPlatformVersion;
        private final ApiRequirements mApiRequirements;

        PlatformVersionMismatchExceptionNotThrownException(CarVersion carVersion,
                PlatformVersion platformVersion, ApiRequirements apiRequirements) {
            super("Test should throw " + PlatformVersionMismatchException.class.getSimpleName()
                    + " when running on unsupported platform: CarVersion=" + carVersion
                    + ", PlatformVersion=" + platformVersion
                    + ", ApiRequirements=" + apiRequirements);

            mCarVersion = carVersion;
            mPlatformVersion = platformVersion;
            mApiRequirements = apiRequirements;
        }

        public CarVersion getCarVersion() {
            return mCarVersion;
        }

        public PlatformVersion getPlatformVersion() {
            return mPlatformVersion;
        }

        public ApiRequirements getApiRequirements() {
            return mApiRequirements;
        }
    }

    public static final class IncompatibleApiRequirementsException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final List<String> mApis;
        private final List<ApiRequirements> mApiRequirements;

        IncompatibleApiRequirementsException(List<String> apis,
                List<ApiRequirements> apiRequirements) {
            super("Incompatible API requirements (apis=" + apis + ", apiRequirements="
                    + apiRequirements + ") on test, consider splitting it into multiple methods");

            mApis = apis;
            mApiRequirements = apiRequirements;
        }

        public List<String> getApis() {
            return mApis;
        }

        public List<ApiRequirements> getApiRequirements() {
            return mApiRequirements;
        }
    }
}
