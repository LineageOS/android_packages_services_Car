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

#ifndef PACKAGES_SCRIPTEXECUTOR_SRC_LUAENGINE_H_
#define PACKAGES_SCRIPTEXECUTOR_SRC_LUAENGINE_H_

#include "ScriptExecutorListener.h"

#include <memory>

extern "C" {
#include "lua.h"
}

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

// Encapsulates Lua script execution environment.
class LuaEngine {
public:
    LuaEngine();

    virtual ~LuaEngine();

    // Returns pointer to Lua state object.
    lua_State* getLuaState();

    // Loads Lua script provided as scriptBody string.
    // Returns 0 if successful. Otherwise returns non-zero Lua error code.
    int loadScript(const char* scriptBody);

    // Pushes a Lua function under provided name into the stack.
    // Returns true if successful.
    bool pushFunction(const char* functionName);

    // Invokes function with the inputs provided in the stack.
    // Assumes that the script body has been already loaded and successfully
    // compiled and run, and all input arguments, and the function have been
    // pushed to the stack.
    // Returns 0 if successful. Otherwise returns non-zero Lua error code.
    int run();

    // Updates stored listener and destroys the previous one.
    static void resetListener(ScriptExecutorListener* listener);

private:
    // Invoked by a running Lua script to store intermediate results.
    // The script will provide the results as a Lua table.
    // We currently support only non-nested fields in the table and the fields can be the following
    // Lua types: boolean, number, integer, and string.
    // The result pushed by Lua is converted to Android Bundle and forwarded to
    // ScriptExecutor service via callback interface.
    // This method returns 0 to indicate that no results were pushed to Lua stack according
    // to Lua C function calling convention.
    // More info: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    static int onSuccess(lua_State* lua);

    // Invoked by a running Lua script to indicate than an error occurred. This is the mechanism to
    // for a script author to receive error logs. The caller script encapsulates all the information
    // about the error that the author wants to provide in a single string parameter.
    // This method returns 0 to indicate that no results were pushed to Lua stack according
    // to Lua C function calling convention.
    // More info: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    static int onError(lua_State* lua);

    // Points to the current listener object.
    // Lua cannot call non-static class methods. We need to access listener object instance in
    // Lua callbacks. Therefore, callbacks callable by Lua are static class methods and the pointer
    // to a listener object needs to be static, since static methods cannot access non-static
    // members.
    // Only one listener is supported at any given time.
    // Since listeners are heap-allocated, the destructor does not need to run at shutdown
    // of the service because the memory allocated to the current listener object will be
    // reclaimed by the OS.
    static ScriptExecutorListener* sListener;

    lua_State* mLuaState;  // owned
};

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com

#endif  // PACKAGES_SCRIPTEXECUTOR_SRC_LUAENGINE_H_
