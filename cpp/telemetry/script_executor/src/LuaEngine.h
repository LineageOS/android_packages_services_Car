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

#ifndef CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_LUAENGINE_H_
#define CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_LUAENGINE_H_

#include "ScriptExecutorListener.h"

#include <memory>

extern "C" {
#include "lua.h"
}

namespace android {
namespace automotive {
namespace telemetry {
namespace script_executor {

// Encapsulates Lua script execution environment.
class LuaEngine {
public:
    explicit LuaEngine(std::unique_ptr<ScriptExecutorListener> listener);

    virtual ~LuaEngine();

private:
    lua_State* mLuaState;  // owned

    std::unique_ptr<ScriptExecutorListener> mListener;
};

}  // namespace script_executor
}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_LUAENGINE_H_
