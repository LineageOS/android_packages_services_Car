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

import static android.car.test.mocks.AndroidMockitoHelper.mockCarGetCarVersion;
import static android.car.test.mocks.AndroidMockitoHelper.mockCarGetPlatformVersion;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.car.Car;
import android.car.CarVersion;
import android.car.PlatformVersion;
import android.car.PlatformVersionMismatchException;
import android.car.annotation.ApiRequirements;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.util.Arrays;

public final class ApiCheckerRuleTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = ApiCheckerRuleTest.class.getSimpleName();

    private static final String INVALID_API = "I.cant.believe.this.is.a.valid.API";
    private static final String VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1 =
            "android.car.test.ApiCheckerRuleTest#requiresCarAndPlatformTiramisu1";

    private final SimpleStatement<Exception> mBaseStatement = new SimpleStatement<>();

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(Car.class);
    }

    @Test
    public void failWhenTestMethodIsMissingAnnotations() throws Throwable {
        Description testMethod = newTestMethod();
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .contains("missing @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsNull() throws Throwable {
        Description testMethod = newTestMethod(new ApiTestAnnotation((String[]) null));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .contains("empty @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsEmpty() throws Throwable {
        Description testMethod = newTestMethod(new ApiTestAnnotation(new String[0]));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .contains("empty @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsInvalid() throws Throwable {
        String methodName = INVALID_API;
        Description testMethod = newTestMethod(new ApiTestAnnotation(methodName));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .contains(methodName);
    }

    @Test
    public void failWhenTestMethodHasValidApiTestAnnotationButNoApiRequirements() throws Throwable {
        String methodName = "android.content.Context#getResources";
        Description testMethod = newTestMethod(new ApiTestAnnotation(methodName));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .contains("@ApiRequirements");
    }

    @Test
    public void passWhenTestMethodHasValidApiTestAnnotation() throws Throwable {
        Description testMethod = newTestMethod(
                new ApiTestAnnotation("android.car.Car#getCarVersion"));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        rule.apply(mBaseStatement, testMethod).evaluate();

        mBaseStatement.assertEvaluated();
    }

    @Test
    public void passWhenTestMethodIsMissingAnnotationsButItsNotEnforced() throws Throwable {
        Description testMethod = newTestMethod();
        ApiCheckerRule rule = new ApiCheckerRule.Builder().disableAnnotationsCheck().build();

        rule.apply(mBaseStatement, testMethod).evaluate();

        mBaseStatement.assertEvaluated();
    }

    @Test
    public void passWhenTestMethodIsMissingApiRequirementsButItsNotEnforced() throws Throwable {
        Description testMethod = newTestMethod(
                new ApiTestAnnotation("android.content.Context#getResources"));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().disableAnnotationsCheck().build();

        rule.apply(mBaseStatement, testMethod).evaluate();

        mBaseStatement.assertEvaluated();
    }

    @Test
    public void failWhenTestMethodRunsOnUnsupportedVersionsAndDoesntThrow() throws Throwable {
        String methodName = VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1;
        Description testMethod = newTestMethod(new ApiTestAnnotation(methodName));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();
        mockCarGetCarVersion(CarVersion.VERSION_CODES.TIRAMISU_1);
        mockCarGetPlatformVersion(PlatformVersion.VERSION_CODES.TIRAMISU_0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(mBaseStatement, testMethod).evaluate());

        assertWithMessage("Exception when platform is not supported").that(e).hasMessageThat()
                .containsMatch(".*Test.*should throw.*"
                        + PlatformVersionMismatchException.class.getSimpleName()
                        + ".*CarVersion=.*major.*33.*minor.*1"
                        + ".*PlatformVersion=.*major.*33.*minor.*0"
                        + ".*ApiRequirements=.*"
                        + "minCarVersion=.*TIRAMISU_1.*minPlatformVersion=.*TIRAMISU_1"
                        + ".*");
    }

    @Test
    public void pasWhenTestMethodRunsOnUnsupportedVersionsAndDoesntThrow() throws Throwable {
        String methodName = VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1;
        Description testMethod = newTestMethod(new ApiTestAnnotation(methodName));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();
        CarVersion carVersion = CarVersion.VERSION_CODES.TIRAMISU_1;
        PlatformVersion platformVersion = PlatformVersion.VERSION_CODES.TIRAMISU_0;
        mockCarGetCarVersion(carVersion);
        mockCarGetPlatformVersion(platformVersion);
        mBaseStatement.failWith(
                new PlatformVersionMismatchException(PlatformVersion.VERSION_CODES.TIRAMISU_1));

        rule.apply(mBaseStatement, testMethod).evaluate();

        mBaseStatement.assertEvaluated();
    }

    @Test
    public void testIsApiSupported_null() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        assertThrows(NullPointerException.class, () -> rule.isApiSupported(null));
    }

    @Test
    public void testIsApiSupported_invalidApi() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        assertThrows(IllegalArgumentException.class, ()-> rule.isApiSupported(INVALID_API));
    }

    @Test
    public void testIsApiSupported_validApiButWithoutApiRequirements() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        assertThrows(IllegalStateException.class,
                () -> rule.isApiSupported("java.lang.Object#toString"));
    }

    @Test
    public void testIsApiSupported_carVersionNotSupported() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();
        String api = VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1;
        mockCarGetCarVersion(CarVersion.VERSION_CODES.TIRAMISU_0);
        mockCarGetPlatformVersion(PlatformVersion.VERSION_CODES.TIRAMISU_1);

        assertWithMessage("isApiSupported(%s) when CarVersion is not supported", api)
                .that(rule.isApiSupported(api)).isFalse();
    }

    @Test
    public void testIsApiSupported_platformVersionNotSupported() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();
        String api = VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1;
        mockCarGetCarVersion(CarVersion.VERSION_CODES.TIRAMISU_1);
        mockCarGetPlatformVersion(PlatformVersion.VERSION_CODES.TIRAMISU_0);

        assertWithMessage("isApiSupported(%s) when PlatformVersion is not supported", api)
                .that(rule.isApiSupported(api)).isFalse();
    }

    @Test
    public void testIsApiSupported_supported() throws Throwable {
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();
        String api = VALID_API_THAT_REQUIRES_CAR_TIRAMISU_1_AND_PLATFORM_TIRAMISU_1;
        mockCarGetCarVersion(CarVersion.VERSION_CODES.TIRAMISU_1);
        mockCarGetPlatformVersion(PlatformVersion.VERSION_CODES.TIRAMISU_1);

        assertWithMessage("isApiSupported(%s) when CarVersion and PlatformVersion are supported",
                api).that(rule.isApiSupported(api)).isTrue();
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_1)
    public void requiresCarAndPlatformTiramisu1() {

    }

    private static Description newTestMethod(ApiTestAnnotation... annotations) {
        return Description.createTestDescription("SomeClass", "someTest", annotations);
    }

    private static class SimpleStatement<T extends Exception> extends Statement {

        private boolean mEvaluated;
        private Throwable mThrowable;

        @Override
        public void evaluate() throws Throwable {
            Log.d(TAG, "evaluate() called");
            mEvaluated = true;
            if (mThrowable != null) {
                Log.d(TAG, "Throwing " + mThrowable);
                throw mThrowable;
            }
        }

        public void failWith(Throwable t) {
            mThrowable = t;
        }

        public void assertEvaluated() {
            assertWithMessage("test method called").that(mEvaluated).isTrue();
        }
    }

    private static final class ApiTestAnnotation implements ApiTest {

        private final String[] mApis;

        ApiTestAnnotation(String... apis) {
            mApis = apis;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return ApiTest.class;
        }

        @Override
        public String[] apis() {
            return mApis;
        }

        @Override
        public String toString() {
            return "ApiTestAnnotation(" + Arrays.toString(mApis) + ")";
        }
    }
}
