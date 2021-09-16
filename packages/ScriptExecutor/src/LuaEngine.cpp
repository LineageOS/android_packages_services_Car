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

#include <sstream>
#include <utility>
#include <vector>

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

// TODO(199415783): Revisit the topic of limits to potentially move it to standalone file.
constexpr int MAX_ARRAY_SIZE = 1000;

// Helper method that goes over Lua table fields one by one and populates PersistableBundle
// object wrapped in BundleWrapper.
// It is assumed that Lua table is located on top of the Lua stack.
//
// Returns false if the conversion encountered unrecoverable error.
// Otherwise, returns true for success.
bool convertLuaTableToBundle(lua_State* lua, BundleWrapper* bundleWrapper,
                             ScriptExecutorListener* listener) {
    // Iterate over Lua table which is expected to be at the top of Lua stack.
    // lua_next call pops the key from the top of the stack and finds the next
    // key-value pair. It returns 0 if the next pair was not found.
    // More on lua_next in: https://www.lua.org/manual/5.3/manual.html#lua_next
    lua_pushnil(lua);  // First key is a null value.
    while (lua_next(lua, /* index = */ -2) != 0) {
        //  'key' is at index -2 and 'value' is at index -1
        // -1 index is the top of the stack.
        // remove 'value' and keep 'key' for next iteration
        // Process each key-value depending on a type and push it to Java PersistableBundle.
        // TODO(199531928): Consider putting limits on key sizes as well.
        const char* key = lua_tostring(lua, /* index = */ -2);
        if (lua_isboolean(lua, /* index = */ -1)) {
            bundleWrapper->putBoolean(key, static_cast<bool>(lua_toboolean(lua, /* index = */ -1)));
        } else if (lua_isinteger(lua, /* index = */ -1)) {
            bundleWrapper->putInteger(key, static_cast<int>(lua_tointeger(lua, /* index = */ -1)));
        } else if (lua_isnumber(lua, /* index = */ -1)) {
            bundleWrapper->putDouble(key, static_cast<double>(lua_tonumber(lua, /* index = */ -1)));
        } else if (lua_isstring(lua, /* index = */ -1)) {
            // TODO(199415783): We need to have a limit on how long these strings could be.
            bundleWrapper->putString(key, lua_tostring(lua, /* index = */ -1));
        } else if (lua_istable(lua, /* index =*/-1)) {
            // Lua uses tables to represent an array.

            // TODO(199438375): Document to users that we expect tables to be either only indexed or
            // keyed but not both. If the table contains consecutively indexed values starting from
            // 1, we will treat it as an array. lua_rawlen call returns the size of the indexed
            // part. We copy this part into an array, but any keyed values in this table are
            // ignored. There is a test that documents this current behavior. If a user wants a
            // nested table to be represented by a PersistableBundle object, they must make sure
            // that the nested table does not contain indexed data, including no key=1.
            const auto kTableLength = lua_rawlen(lua, -1);
            if (kTableLength > MAX_ARRAY_SIZE) {
                std::ostringstream out;
                out << "Returned table " << key << " exceeds maximum allowed size of "
                    << MAX_ARRAY_SIZE
                    << " elements. This key-value cannot be unpacked successfully. This error "
                       "is unrecoverable.";
                listener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                                  out.str().c_str(), "");
                return false;
            }
            if (kTableLength > 0) {
                std::vector<int64_t> arr;
                arr.reserve(kTableLength);  // pre-allocate enough memory for performance.
                for (int i = 0; i < kTableLength; i++) {
                    lua_rawgeti(lua, -1, i + 1);
                    if (!lua_isinteger(lua, /* index = */ -1)) {
                        std::ostringstream out;
                        out << "Returned table " << key
                            << " contains values of types other than expected integer. This "
                               "key-value cannot be unpacked successfully. This error is "
                               "unrecoverable.";
                        listener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                                          out.str().c_str(), "");
                        lua_pop(lua, 1);
                        return false;
                    }
                    arr.push_back(lua_tointeger(lua, /* index = */ -1));
                    lua_pop(lua, 1);
                }
                bundleWrapper->putLongArray(key, arr);
            }
        } else {
            // not supported yet...
            // TODO(199439259): Instead of logging here, log and send to user instead, and continue
            // unpacking the rest of the table.
            LOG(WARNING) << "key=" << key << " has a Lua type which is not supported yet. "
                         << "The bundle object will not have this key-value pair.";
        }
        // Pop value from the stack, keep the key for the next iteration.
        lua_pop(lua, 1);
        // The key is at index -1, the table is at index -2 now.
    }
    return true;
}

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
    lua_register(mLuaState, "on_script_finished", LuaEngine::onScriptFinished);
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
        sListener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_success can push only a single parameter from Lua - a Lua table",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // Helper object to create and populate Java PersistableBundle object.
    BundleWrapper bundleWrapper(sListener->getCurrentJNIEnv());
    if (convertLuaTableToBundle(lua, &bundleWrapper, sListener)) {
        // Forward the populated Bundle object to Java callback.
        sListener->onSuccess(bundleWrapper.getBundle());
    }

    // We explicitly must tell Lua how many results we return, which is 0 in this case.
    // More on the topic: https://www.lua.org/manual/5.3/manual.html#lua_CFunction
    return ZERO_RETURNED_RESULTS;
}

int LuaEngine::onScriptFinished(lua_State* lua) {
    // Any script we run can call on_success only with a single argument of Lua table type.
    if (lua_gettop(lua) != 1 || !lua_istable(lua, /* index =*/-1)) {
        sListener->onError(IScriptExecutorConstants::ERROR_TYPE_LUA_SCRIPT_ERROR,
                           "on_script_finished can push only a single parameter from Lua - a Lua "
                           "table",
                           "");
        return ZERO_RETURNED_RESULTS;
    }

    // Helper object to create and populate Java PersistableBundle object.
    BundleWrapper bundleWrapper(sListener->getCurrentJNIEnv());
    if (convertLuaTableToBundle(lua, &bundleWrapper, sListener)) {
        // Forward the populated Bundle object to Java callback.
        sListener->onScriptFinished(bundleWrapper.getBundle());
    }

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
