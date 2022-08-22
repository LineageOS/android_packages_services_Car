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

import static android.car.test.ApiHelper.resolve;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.CarVersion;
import android.content.Context;
import android.os.Handler;
import android.os.Parcelable;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public final class ApiHelperTest {

    // TODO(b/242571576): add parameterized test class for invalid values (i.e., that return null)

    @Test
    public void testResolve_null() {
        assertThrows(NullPointerException.class, () -> resolve(null));
    }

    @Test
    public void testResolve_empty() {
        assertWithMessage("resolve()").that(resolve("")).isNull();
        assertWithMessage("resolve( )").that(resolve(" ")).isNull();
    }

    @Test
    public void testResolve_methodWithoutParameters() {
        assertMethod("android.car.Car#getCarVersion", Car.class, "getCarVersion");
    }

    @Test
    public void testResolve_methodWithOneParameter_fullyQualified() {
        assertInvalidApi("android.car.Car#createCar(android.content.Context)");
    }

    @Test
    public void testResolve_methodWithOneParameter() {
        assertMethod("android.car.Car#createCar(Context)", Car.class, "createCar", Context.class);
    }

    @Test
    public void testResolve_methodWithOneParameterFromJavaLang() {
        assertMethod("android.car.Car#isFeatureEnabled(String)", Car.class, "isFeatureEnabled",
                String.class);
    }

    @Test
    public void testResolve_methodWithMultipleParametersIncludingFromJavaLang() {
        assertMethod("android.car.Car#createCar(Context,Handler,long,CarServiceLifecycleListener)",
                Car.class, "createCar",
                Context.class, Handler.class, long.class, CarServiceLifecycleListener.class);
    }

    @Test
    public void testResolve_methodWithMultipleParametersWithSpaces() {
        assertMethod("android.car.Car#createCar( Context,Handler, long ,"
                + "CarServiceLifecycleListener )",
                Car.class, "createCar",
                Context.class, Handler.class, long.class, CarServiceLifecycleListener.class);
    }

    @Test
    public void testResolve_methodWithOverloadedParameters() {
        assertMethod("android.car.Car#createCar(Context,Handler)",
                Car.class, "createCar",
                Context.class, Handler.class);

        assertMethod("android.car.Car#createCar(Context)",
                Car.class, "createCar",
                Context.class);
    }

    @Test
    public void testResolve_singleField() {
        assertField("android.car.Car#API_VERSION_MAJOR_INT", Car.class, int.class,
                "API_VERSION_MAJOR_INT");
    }

    @Test
    public void testResolve_creator() {
        assertField("android.car.CarVersion#CREATOR", CarVersion.class, Parcelable.Creator.class,
                "CREATOR");
    }

    @Test
    public void testResolve_nestedField_valid() {
        assertField("android.car.CarVersion.VERSION_CODES#TIRAMISU_0",
                CarVersion.VERSION_CODES.class, CarVersion.class, "TIRAMISU_0");
    }

    @Test
    public void testResolve_nestedField_invalid() {
        assertInvalidApi("android.car.CarVersion$VERSION_CODES");
        assertInvalidApi("android.car.CarVersion$VERSION_CODES.TIRAMISU_0");
        assertInvalidApi("android.car.CarVersion$VERSION_CODES#TIRAMISU_0");
        assertInvalidApi("android.car.CarVersion#VERSION_CODES");
        assertInvalidApi("android.car.CarVersion.VERSION_CODES.TIRAMISU_0");
    }

    private static void assertInvalidApi(String api) {
        assertWithMessage("invalid API").that(resolve(api)).isNull();
    }

    private static Method assertMethod(String api, Class<?> expectedClass, String expectedName,
            Class<?>...expectedParameterTypes) {
        Method method = assertMember(api, Method.class, expectedClass, expectedName);
        assertWithMessage("parameter types of %s", method).that(method.getParameterTypes())
                .asList().containsExactlyElementsIn(expectedParameterTypes);
        return method;
    }

    private static Field assertField(String api, Class<?> expectedDeclaringClass,
            Class<?> expectedFieldClass, String expectedName) {
        Field field = assertMember(api, Field.class, expectedDeclaringClass, expectedName);
        assertWithMessage("type of %s", field).that(field.getType()).isEqualTo(expectedFieldClass);
        return field;
    }

    private static <M extends Member> M assertMember(String api, Class<M> expectedMemberType,
            Class<?> expectedDeclaringClass, String expectedName) {
        Member member = resolve(api);
        assertWithMessage("resolve(%s)", api).that(member).isNotNull();
        assertWithMessage("member type of %s", member).that(member)
                .isInstanceOf(expectedMemberType);
        assertWithMessage("declaring class of %s", member).that(member.getDeclaringClass())
                .isEqualTo(expectedDeclaringClass);
        assertWithMessage("name of %s", member).that(member.getName()).isEqualTo(expectedName);
        return expectedMemberType.cast(member);
    }
}
