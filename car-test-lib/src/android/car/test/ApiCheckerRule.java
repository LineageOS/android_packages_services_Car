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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.Nullable;
import android.car.Car;
import android.car.CarVersion;
import android.car.PlatformVersion;
import android.car.PlatformVersionMismatchException;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ApiRequirements;
import android.car.test.ApiCheckerRule.UnsupportedVersionTest.Behavior;
import android.text.TextUtils;
import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
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
    // - CDD support (and its own @ApiRequirements)

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
        return getAnnotation(ApiRequirements.class, member);
    }

    @SuppressWarnings("deprecation")
    private static AddedInOrBefore getAddedInOrBefore(Member member) {
        return getAnnotation(AddedInOrBefore.class, member);
    }

    private static <T extends Annotation> T getAnnotation(Class<T> annotationClass, Member member) {
        if (member instanceof Field) {
            return ((Field) member).getAnnotation(annotationClass);
        }
        if (member instanceof Method) {
            return ((Method) member).getAnnotation(annotationClass);
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

                // Optional annotations that change the behavior of the rule
                SupportedVersionTest supportedVersionTest = null;
                UnsupportedVersionTest unsupportedVersionTest = null;

                // Other relevant annotations
                @SuppressWarnings("deprecation")
                AddedInOrBefore addedInOrBefore = null;

                for (Annotation annotation : description.getAnnotations()) {
                    if (DBG) {
                        Log.d(TAG, "Annotation: " + annotation);
                    }
                    if (annotation instanceof ApiTest) {
                        apiTest = (ApiTest) annotation;
                        continue;
                    }
                    if (annotation instanceof SupportedVersionTest) {
                        supportedVersionTest = (SupportedVersionTest) annotation;
                        continue;
                    }
                    if (annotation instanceof UnsupportedVersionTest) {
                        unsupportedVersionTest = (UnsupportedVersionTest) annotation;
                        continue;
                    }
                }

                if (DBG) {
                    Log.d(TAG, "Relevant annotations: ApiTest=" + apiTest
                            + " SupportedVersionTest=" + supportedVersionTest
                            + " AddedInOrBefore=" + addedInOrBefore
                            + " UnsupportedVersionTest=" + unsupportedVersionTest);
                }

                validateOptionalAnnotations(description.getTestClass(), supportedVersionTest,
                        unsupportedVersionTest);

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
                        if (apiRequirements == null && addedInOrBefore == null) {
                            addedInOrBefore = getAddedInOrBefore(member);
                            if (DBG) {
                                Log.d(TAG, "No @ApiRequirements on " + api + "; trying "
                                        + "@AddedInOrBefore instead: " + addedInOrBefore);
                            }
                            continue;
                        }
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

                if (firstApiRequirements == null && addedInOrBefore == null) {
                    if (mEnforceTestApiAnnotations) {
                        throw new IllegalStateException("Missing @ApiRequirements "
                                + "or @AddedInOrBefore");
                    } else {
                        Log.w(TAG, "Test " + description + " doesn't have required "
                                + "@ApiRequirements or @AddedInOrBefore but rule is not enforcing"
                                + " them");
                    }
                    base.evaluate();
                    return;
                }

                if (!compatibleApis) {
                    throw new IncompatibleApiRequirementsException(allApis, allApiRequirements);
                }

                // Finally, run the test and assert results depending on whether it's supported or
                // not
                apply(base, description, firstApiRequirements, supportedVersionTest,
                        unsupportedVersionTest);
            }
        };
    } // apply

    private void validateOptionalAnnotations(Class<?> textClass,
            @Nullable SupportedVersionTest supportedVersionTest,
            @Nullable UnsupportedVersionTest unsupportedVersionTest) {
        if (unsupportedVersionTest != null) {
            if (supportedVersionTest != null) {
                throw new IllegalStateException("test must be annotated with either "
                        + "supportedVersionTest or unsupportedVersionTest, not both");
            }

            // Test class must have a counterpart supportedVersionTest
            String supportedVersionTestMethod = unsupportedVersionTest.supportedVersionTest();
            if (TextUtils.isEmpty(supportedVersionTestMethod)) {
                throw new IllegalStateException("missing supportedVersionTest on "
                        + supportedVersionTestMethod);
            }

            Method method = null;
            Class<?>[] noParams = {};
            try {
                method = textClass.getDeclaredMethod(supportedVersionTestMethod, noParams);
            } catch (Exception e) {
                Log.w(TAG, "Error getting method named " + supportedVersionTestMethod
                        + " on class " + textClass, e);
                throw new IllegalStateException(
                        "invalid supportedVersionTest on " + supportedVersionTestMethod + e);
            }
            // And it must be annotated with @SupportedVersionTest
            SupportedVersionTest supportedVersionTestAnnotation =
                    method.getAnnotation(SupportedVersionTest.class);
            if (supportedVersionTestAnnotation == null) {
                throw new IllegalStateException(
                        "invalid supportedVersionTest on " + supportedVersionTestMethod
                        + ": it's not annotated with @SupportedVersionTest");
            }
        }
    }

    private void apply(Statement base, Description description,
            @Nullable ApiRequirements apiRequirements,
            @Nullable SupportedVersionTest supportedVersionTest,
            @Nullable UnsupportedVersionTest unsupportedVersionTest)
            throws Throwable {
        if (apiRequirements == null) {
            Log.w(TAG, "No @ApiRequirements on " + description.getDisplayName()
                    + " (most likely it's annotated with @AddedInOrBefore), running it always");
            base.evaluate();
            return;
        }
        if (isSupported(apiRequirements)) {
            applyOnSupportedVersion(base, description, apiRequirements, unsupportedVersionTest);
            return;
        }

        applyOnUnsupportedVersion(base, description, apiRequirements, supportedVersionTest,
                unsupportedVersionTest);
    }

    private void applyOnSupportedVersion(Statement base, Description description,
            ApiRequirements apiRequirements,
            @Nullable UnsupportedVersionTest unsupportedVersionTest)
            throws Throwable {
        if (unsupportedVersionTest == null) {
            if (DBG) {
                Log.d(TAG, "Car / Platform combo is supported, running "
                        + description.getDisplayName());
            }
            base.evaluate();
            return;
        }

        Log.i(TAG, "Car / Platform combo IS supported, but ignoring "
                + description.getDisplayName() + " because it's annotated with "
                + unsupportedVersionTest);

        throw new ExpectedVersionAssumptionViolationException(unsupportedVersionTest,
                Car.getCarVersion(), Car.getPlatformVersion(), apiRequirements);
    }

    private void applyOnUnsupportedVersion(Statement base, Description description,
            ApiRequirements apiRequirements,  @Nullable SupportedVersionTest supportedVersionTest,
            @Nullable UnsupportedVersionTest unsupportedVersionTest)
            throws Throwable {
        Behavior behavior = unsupportedVersionTest == null ? null
                : unsupportedVersionTest.behavior();
        if (supportedVersionTest == null && !Behavior.EXPECT_PASS.equals(behavior)) {
            Log.i(TAG, "Car / Platform combo is NOT supported, running "
                    + description.getDisplayName() + " but expecting "
                          + "PlatformVersionMismatchException");
            try {
                base.evaluate();
                throw new PlatformVersionMismatchExceptionNotThrownException(
                        Car.getCarVersion(), Car.getPlatformVersion(), apiRequirements);
            } catch (PlatformVersionMismatchException e) {
                if (DBG) {
                    Log.d(TAG, "Exception thrown as expected: " + e);
                }
            }
            return;
        }

        if (supportedVersionTest != null) {
            Log.i(TAG, "Car / Platform combo is NOT supported, but ignoring "
                    + description.getDisplayName() + " because it's annotated with "
                    + supportedVersionTest);

            throw new ExpectedVersionAssumptionViolationException(supportedVersionTest,
                    Car.getCarVersion(), Car.getPlatformVersion(), apiRequirements);
        }

        // At this point, it's annotated with RUN_ALWAYS
        Log.i(TAG, "Car / Platform combo is NOT supported but running anyways becaucase test is"
                + " annotated with " + unsupportedVersionTest);
        base.evaluate();
    }

    /**
     * Defines the behavior of a test when it's run in an unsupported device (when it's run in a
     * supported device, the rule will throw a {@link ExpectedVersionAssumptionViolationException}
     * exception).
     *
     * <p>Without this annotation, a test is expected to throw a
     * {@link PlatformVersionMismatchException} when running in an unsupported version.
     *
     * <p><b>Note: </b>a test annotated with this annotation <b>MUST</b> have a counterpart test
     * annotated with {@link SupportedVersionTest}.
     */
    @Retention(RUNTIME)
    @Target({TYPE, METHOD})
    public @interface UnsupportedVersionTest {

        /**
         * Name of the counterpart test should be run on supported versions; such test must be
         * annoted with {@link SupportedVersionTest}.
         */
        String supportedVersionTest();

        /**
         * Behavior of the test when it's run on unsupported versions.
         */
        Behavior behavior() default Behavior.EXPECT_THROWS_VERSION_MISMATCH_EXCEPTION;

        @SuppressWarnings("Enum")
        enum Behavior {
            /**
             * Rule will run the test and assert it throws a
             * {@link PlatformVersionMismatchException}.
             */
            EXPECT_THROWS_VERSION_MISMATCH_EXCEPTION,

            /** Rule will run the test and assume it will pass.*/
            EXPECT_PASS
        }
    }

    /**
     * Defines a test to be a counterpart of a test annotated with {@link UnsupportedVersionTest}.
     *
     * <p>Such test will be run as usual on supported devices, but will throw a
     * {@link ExpectedVersionAssumptionViolationException} when running on unsupported devices.
     *
     */
    @Retention(RUNTIME)
    @Target({TYPE, METHOD})
    public @interface SupportedVersionTest {

    }

    public static final class ExpectedVersionAssumptionViolationException
            extends AssumptionViolatedException {

        private static final long serialVersionUID = 1L;

        private final CarVersion mCarVersion;
        private final PlatformVersion mPlatformVersion;
        private final ApiRequirements mApiRequirements;

        ExpectedVersionAssumptionViolationException(Annotation annotation, CarVersion carVersion,
                PlatformVersion platformVersion, ApiRequirements apiRequirements) {
            super("Test annotated with @" + annotation.getClass().getCanonicalName()
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
