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

#include "LuaEngine.h"

#include "BundleWrapper.h"

#include <android-base/logging.h>
#include <com/android/car/telemetry/scriptexecutorinterface/IScriptExecutorConstants.h>

#include <utility>

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

using ::com::android::car::telemetry::scriptexecutorinterface::IScriptExecutorConstants;

namespace {

enum LuaNumReturnedResults {
    ZERO_RETURNED_RESULTS = 0,
};

}  // namespace

ScriptExecutorListener* LuaEngine::sListener = nullptr;

LuaEngine::LuaEngine() {
    // Instantiate Lua environment
    mLuaState = luaL_newstate();
    luaL_openlibs(mLuaState);
}

LuaEngine::~LuaEngine() {
    lua_close(mLuaState);
}

lua_State* LuaEngine::getLuaState() {
    return mLuaState;
}

void LuaEngine::resetListener(ScriptExecutorListener* listener) {
    if (sListener != nullptr) {
        delete sListener;
    }
    sListener = listener;
}

int LuaEngine::loadScript(const char* scriptBody) {
    // As the first step in Lua script execution we want to load
    // the body of the script into Lua stack and have it processed by Lua
    // to catch any errors.
    // More on luaL_dostring: https://www.lua.org/manual/5.3/manual.html#lual_dostring
    // If error, pushes the error object into the stack.
    const auto status = luaL_dostring(mLuaState, scriptBody);
    if (status) {
        // Removes error object from the stack.
        // Lua stack must be properly maintained due to its limited size,
        // ~20 elements and its critical function because all interaction with
        // Lua happens via the stack.
        // Starting read about Lua stack: https://www.lua.org/pil/24.2.html
        // TODO(b/192284232): add test case to trigger this.
        lua_pop(mLuaState, 1);
        return status;
    }

    // Register limited set of reserved methods for Lua to call native side.
    lua_register(mLuaState, "on_success", LuaEngine::onSuccess);
    lua_register(mLuaState, "on_error", LuaEngine::onError);
    return status;
}

bool LuaEngine::pushFunction(const char* functionName) {
    // Interaction between native code and Lua happens via Lua stack.
    // In such model, a caller first pushes the name of the function
    // that needs to be called, followed by the function's input
    // arguments, one input value pushed at a time.
    // More info: https://www.lua.org/pil/24.2.html
    lua_getglobal(mLuaState, functionName);
    const auto status = lua_isfunction(mLuaState, /*idx= */ -1);
    // TODO(b/192284785): add test case for wrong function name in Lua.
    if (status == 0) lua_pop(mLuaState, 1);
    return status;
}

int LuaEngine::run() {
    // Performs blocking call of the provided Lua function. Assumes all
    // input arguments are in the Lua stack as well in proper order.
    // On how to call Lua functions: https://www.lua.org/pil/25.2.html
    // Doc on lua_pcall: https://www.lua.org/manual/5.3/manual.html#lua_pcall
    // TODO(b/189241508): Once we implement publishedData parsing, nargs should
    // change from 1 to 2.
    // TODO(b/192284612): add test case for failed call.
    return lua_pcall(mLuaState, /* nargs= */ 1, /* nresults= */ 0, /*errfunc= */ 0);
}

int LuaEngine::onSuccess(lua_State* lua) {
    // Any script we run can call on_success only with a single argument of Lua table type.
    if (lua_gettop(lua) != 1 || !lua_istable(lua, /* index =*/-1)) {
        // TODO(b/193565932): Return programming error through binder callback interface.
        LOG(ERROR) << "Only a single input argument, a Lua table object, expected here";
    }

    // Helper object to create and populate Java Bundle object.
    BundleWrapper bundleWrapper(sListener->getCurrentJNIEnv());
    // Iterate over Lua table which is expected to be at the top of Lua stack.
    // lua_next call pops the key from the top of the stack and finds the next
    // key-value pair for the popped key. It returns 0 if the next pair was not found.
    // More on lua_next in: https://www.lua.org/manual/5.3/manual.html#lua_next
    lua_pushnil(lua);  // First key is a null value.
    while (lua_next(lua, /* index = */ -2) != 0) {
        //  'key' is at index -2 and 'value' is at index -1
        // -1 index is the top of the stack.
        // remove 'value' and keep 'key' for next iteration
        // Process each key-value depending on a type and push it to Java Bundle.
        const char* key = lua_tostring(lua, /* index = */ -2);
        if (lua_isboolean(lua, /* index = */ -1)) {
            bundleWrapper.putBoolean(key, static_cast<bool>(lua_toboolean(lua, /* index = */ -1)));
        } else if (lua_isinteger(lua, /* index = */ -1)) {
            bundleWrapper.putInteger(key, static_cast<int>(lua_tointeger(lua, /* index = */ -1)));
        } else if (lua_isnumber(lua, /* index = */ -1)) {
            bundleWrapper.putDouble(key, static_cast<double>(lua_tonumber(lua, /* index = */ -1)));
        } else if (lua_isstring(lua, /* index = */ -1)) {
            bundleWrapper.putString(key, lua_tostring(lua, /* index = */ -1));
        } else {
            // not supported yet...
            LOG(WARNING) << "key=" << key << " has a Lua type which is not supported yet. "
                         << "The bundle object will not have this key-value pair.";
        }
        // Pop 1 element from the stack.
        lua_pop(lua, 1);
        // The key is at index -1, the table is at index -2 now.
    }

    // Forward the populated Bundle object to Java callback.
    sListener->onSuccess(bundleWrapper.getBundle());
    // We explicitly must tell Lua how many results we return, which is 0 in this case.
    // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onError(lua_State* lua) {
    // Any script we run can call on_error only with a single argument of Lua string type.
    if (lua_gettop(lua) != 1 || !lua_isstring(lua, /* index = */ -1)) {
        sListener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_error can push only a single string parameter from Lua", "");
        return ZERO_RETURNED_RESULTS;
    }
    sListener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                       lua_tostring(lua, /* index = */ -1), /* stackTrace =*/"");
    return ZERO_RETURNED_RESULTS;
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
