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

package android.car;

import static com.google.common.truth.Truth.assertWithMessage;

import android.car.annotation.AddedIn;
import android.car.annotation.AddedInOrBefore;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class AnnotationTest {
    private static final String TAG = AnnotationTest.class.getSimpleName();

    // TODO(b/192692829): Add all Car API classes to this list.2
    private static final String[] CAR_API_CLASSES = new String[] {
            "android.car.Car",
            "android.car.user.CarUserManager"
    };

    @Test
    public void testClassAddedInAnnotation() throws Exception {
        List<String> errorsNoAnnotation = new ArrayList<>();
        List<String> errorsExtraAnnotation = new ArrayList<>();

        for (int i = 0; i < CAR_API_CLASSES.length; i++) {
            String className = CAR_API_CLASSES[i];
            Field[] fields = Class.forName(className).getDeclaredFields();
            for (int j = 0; j < fields.length; j++) {
                Field field = fields[j];
                boolean isAnnotated = containsAddedInAnnotation(field);
                boolean isPrivate = Modifier.isPrivate(field.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " FIELD: " + field.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " FIELD: " + field.getName());
                }
            }

            Method[] methods = Class.forName(className).getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];

                // These are some internal methods
                if (method.getName().contains("$")) continue;

                boolean isAnnotated = containsAddedInAnnotation(method);
                boolean isPrivate = Modifier.isPrivate(method.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " METHOD: " + method.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " METHOD: " + method.getName());
                }
            }
        }

        StringBuilder errorFlatten = new StringBuilder();
        if (!errorsNoAnnotation.isEmpty()) {
            errorFlatten.append("Errors:\nNo AddedIn annotation found for-\n");
            for (int i = 0; i < errorsNoAnnotation.size(); i++) {
                errorFlatten.append(errorsNoAnnotation.get(i) + "\n");
            }
        }

        if (!errorsExtraAnnotation.isEmpty()) {
            errorFlatten.append("\nErrors:\nExtra AddedIn annotation found for-\n");
            for (int i = 0; i < errorsExtraAnnotation.size(); i++) {
                errorFlatten.append(errorsExtraAnnotation.get(i) + "\n");
            }
        }

        assertWithMessage(errorFlatten.toString())
                .that(errorsExtraAnnotation.size() + errorsNoAnnotation.size()).isEqualTo(0);
    }

    private boolean containsAddedInAnnotation(Field field) {
        return field.getAnnotation(AddedInOrBefore.class) != null
                || field.getAnnotation(AddedIn.class) != null;
    }

    private boolean containsAddedInAnnotation(Method method) {
        return method.getAnnotation(AddedInOrBefore.class) != null
                || method.getAnnotation(AddedIn.class) != null;
    }
}
