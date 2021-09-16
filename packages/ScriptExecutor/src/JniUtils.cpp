/*
 * Copyright (c) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "JniUtils.h"

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

void pushBundleToLuaTable(JNIEnv* env, LuaEngine* luaEngine, jobject bundle) {
    lua_newtable(luaEngine->getLuaState());
    // null bundle object is allowed. We will treat it as an empty table.
    if (bundle == nullptr) {
        return;
    }

    // TODO(b/188832769): Consider caching some of these JNI references for
    // performance reasons.
    jclass persistableBundleClass = env->FindClass("android/os/PersistableBundle");
    jmethodID getKeySetMethod =
            env->GetMethodID(persistableBundleClass, "keySet", "()Ljava/util/Set;");
    jobject keys = env->CallObjectMethod(bundle, getKeySetMethod);
    jclass setClass = env->FindClass("java/util/Set");
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject keySetIteratorObject = env->CallObjectMethod(keys, iteratorMethod);

    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jclass integerClass = env->FindClass("java/lang/Integer");
    jclass numberClass = env->FindClass("java/lang/Number");
    jclass stringClass = env->FindClass("java/lang/String");
    jclass intArrayClass = env->FindClass("[I");
    jclass longArrayClass = env->FindClass("[J");
    // TODO(b/188816922): Handle more types such as float and integer arrays,
    // and perhaps nested Bundles.

    jmethodID getMethod = env->GetMethodID(persistableBundleClass, "get",
                                           "(Ljava/lang/String;)Ljava/lang/Object;");

    // Iterate over key set of the bundle one key at a time.
    while (env->CallBooleanMethod(keySetIteratorObject, hasNextMethod)) {
        // Read the value object that corresponds to this key.
        jstring key = (jstring)env->CallObjectMethod(keySetIteratorObject, nextMethod);
        jobject value = env->CallObjectMethod(bundle, getMethod, key);

        // Get the value of the type, extract it accordingly from the bundle and
        // push the extracted value and the key to the Lua table.
        if (env->IsInstanceOf(value, booleanClass)) {
            jmethodID boolMethod = env->GetMethodID(booleanClass, "booleanValue", "()Z");
            bool boolValue = static_cast<bool>(env->CallBooleanMethod(value, boolMethod));
            lua_pushboolean(luaEngine->getLuaState(), boolValue);
        } else if (env->IsInstanceOf(value, integerClass)) {
            jmethodID intMethod = env->GetMethodID(integerClass, "intValue", "()I");
            lua_pushinteger(luaEngine->getLuaState(), env->CallIntMethod(value, intMethod));
        } else if (env->IsInstanceOf(value, numberClass)) {
            // Condense other numeric types using one class. Because lua supports only
            // integer or double, and we handled integer in previous if clause.
            jmethodID numberMethod = env->GetMethodID(numberClass, "doubleValue", "()D");
            /* Pushes a double onto the stack */
            lua_pushnumber(luaEngine->getLuaState(), env->CallDoubleMethod(value, numberMethod));
        } else if (env->IsInstanceOf(value, stringClass)) {
            const char* rawStringValue = env->GetStringUTFChars((jstring)value, nullptr);
            lua_pushstring(luaEngine->getLuaState(), rawStringValue);
            env->ReleaseStringUTFChars((jstring)value, rawStringValue);
        } else if (env->IsInstanceOf(value, intArrayClass)) {
            jintArray intArray = static_cast<jintArray>(value);
            const auto kLength = env->GetArrayLength(intArray);
            // Arrays are represented as a table of sequential elements in Lua.
            // We are creating a nested table to represent this array. We specify number of elements
            // in the Java array to preallocate memory accordingly.
            lua_createtable(luaEngine->getLuaState(), kLength, 0);
            jint* rawIntArray = env->GetIntArrayElements(intArray, nullptr);
            // Fills in the table at stack idx -2 with key value pairs, where key is a
            // Lua index and value is an integer from the byte array at that index
            for (int i = 0; i < kLength; i++) {
                // Stack at index -1 is rawIntArray[i] after this push.
                lua_pushinteger(luaEngine->getLuaState(), rawIntArray[i]);
                lua_rawseti(luaEngine->getLuaState(), /* idx= */ -2,
                            i + 1);  // lua index starts from 1
            }
            // JNI_ABORT is used because we do not need to copy back elements.
            env->ReleaseIntArrayElements(intArray, rawIntArray, JNI_ABORT);
        } else if (env->IsInstanceOf(value, longArrayClass)) {
            jlongArray longArray = static_cast<jlongArray>(value);
            const auto kLength = env->GetArrayLength(longArray);
            // Arrays are represented as a table of sequential elements in Lua.
            // We are creating a nested table to represent this array. We specify number of elements
            // in the Java array to preallocate memory accordingly.
            lua_createtable(luaEngine->getLuaState(), kLength, 0);
            jlong* rawLongArray = env->GetLongArrayElements(longArray, nullptr);
            // Fills in the table at stack idx -2 with key value pairs, where key is a
            // Lua index and value is an integer from the byte array at that index
            for (int i = 0; i < kLength; i++) {
                lua_pushinteger(luaEngine->getLuaState(), rawLongArray[i]);
                lua_rawseti(luaEngine->getLuaState(), /* idx= */ -2,
                            i + 1);  // lua index starts from 1
            }
            // JNI_ABORT is used because we do not need to copy back elements.
            env->ReleaseLongArrayElements(longArray, rawLongArray, JNI_ABORT);
        } else {
            // Other types are not implemented yet, skipping.
            continue;
        }

        const char* rawKey = env->GetStringUTFChars(key, nullptr);
        // table[rawKey] = value, where value is on top of the stack,
        // and the table is the next element in the stack.
        lua_setfield(luaEngine->getLuaState(), /* idx= */ -2, rawKey);
        env->ReleaseStringUTFChars(key, rawKey);
    }
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
