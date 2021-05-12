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

#include <utility>

extern "C" {
#include "lauxlib.h"
#include "lualib.h"
}

namespace android {
namespace automotive {
namespace telemetry {
namespace script_executor {

LuaEngine::LuaEngine(std::unique_ptr<ScriptExecutorListener> listener) :
      mListener(std::move(listener)) {
    mLuaState = luaL_newstate();
    luaL_openlibs(mLuaState);
}

LuaEngine::~LuaEngine() {
    lua_close(mLuaState);
}

}  // namespace script_executor
}  // namespace telemetry
}  // namespace automotive
}  // namespace android
