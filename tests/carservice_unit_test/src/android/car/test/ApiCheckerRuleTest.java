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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.util.Log;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.util.Arrays;

public final class ApiCheckerRuleTest {

    private static final String TAG = ApiCheckerRuleTest.class.getSimpleName();

    private final SimpleStatement<Exception> mBaseStatement = new SimpleStatement<>();

    @Test
    public void failWhenTestMethodIsMissingAnnotations() throws Throwable {
        Description testMethod = newTestMethod();
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e.getMessage())
                .contains("missing @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsNull() throws Throwable {
        Description testMethod = newTestMethod(new ApiTestAnnotation((String[]) null));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e.getMessage())
                .contains("empty @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsEmpty() throws Throwable {
        Description testMethod = newTestMethod(new ApiTestAnnotation(new String[0]));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e.getMessage())
                .contains("empty @ApiTest annotation");
    }

    @Test
    public void passWhenTestMethodHasApiTestAnnotationButItsInvalid() throws Throwable {
        String methodName = "I.cant.believe.this.is.a.valid.method.name";
        Description testMethod = newTestMethod(new ApiTestAnnotation(methodName));
        ApiCheckerRule rule = new ApiCheckerRule.Builder().build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rule.apply(new SimpleStatement<>(), testMethod).evaluate());

        assertWithMessage("exception (%s) message", e).that(e.getMessage()).contains(methodName);
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

    private static Description newTestMethod(ApiTestAnnotation... annotations) {
        return Description.createTestDescription("SomeClass", "someTest", annotations);
    }

    private static class SimpleStatement<T extends Exception> extends Statement {

        private boolean mEvaluated;

        @Override
        public void evaluate() throws Throwable {
            Log.d(TAG, "SimpleStatement.evaluate()");
            mEvaluated = true;
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
