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

#include "lua_engine.h"

#include <cstring>
#include <lua.hpp>
#include <string>
#include <vector>

namespace lua_interpreter {

std::vector<std::string> LuaEngine::output_;

LuaEngine::LuaEngine() {
  lua_state_ = luaL_newstate();
  luaL_openlibs(lua_state_);
}

LuaEngine::~LuaEngine() { lua_close(lua_state_); }

char** LuaEngine::StringVectorToCharArray(std::vector<std::string> vector) {
  if (vector.size() == 0) {
    return nullptr;
  }

  char** array = new char*[vector.size()];
  for (unsigned int i = 0; i < vector.size(); i++) {
    // Size + 1 is for the null-terminating character.
    array[i] = new char[vector[i].size() + 1];
    snprintf(array[i], vector[i].size() + 1, "%s", vector[i].c_str());
  }

  return array;
}

std::vector<std::string> LuaEngine::ExecuteScript(std::string script_body) {
  output_.clear();
  const auto load_status = luaL_loadstring(lua_state_, script_body.data());
  if (load_status != LUA_OK) {
    const char* error = lua_tostring(lua_state_, lua_gettop(lua_state_));
    lua_pop(lua_state_, lua_gettop(lua_state_));
    output_.push_back(std::string("Error encountered while loading the "
                                  "script. A possible cause could be "
                                  "syntax errors in the script. Error: ") +
                      std::string(error));
    return output_;
  }

  const auto run_status =
      lua_pcall(lua_state_, /*nargs=*/0, LUA_MULTRET, /*msgh=*/0);
  if (run_status != LUA_OK) {
    const char* error = lua_tostring(lua_state_, lua_gettop(lua_state_));
    lua_pop(lua_state_, lua_gettop(lua_state_));
    output_.push_back(
        std::string("Error encountered while running the script. The returned "
                    "error code = ") +
        std::to_string(run_status) +
        std::string(". Refer to lua.h file of Lua C API library for error code "
                    "definitions. Error: ") +
        std::string(error));
    return output_;
  }

  if (lua_gettop(lua_state_) > 0) {
    DumpStack(lua_state_);
    lua_pop(lua_state_, lua_gettop(lua_state_));
  }

  return output_;
}

int LuaEngine::DumpStack(lua_State* lua_state) {
  int num_args = lua_gettop(lua_state);
  for (int i = 1; i <= num_args; i++) {
    const char* string = lua_tostring(lua_state, i);
    output_.push_back(string);
    output_.push_back("\t");
  }
  output_.push_back("\n");
  return 0;
}

extern "C" {
void FreeLuaOutput(LuaOutput* lua_output) {
  for (int i = 0; i < lua_output->size; i++) {
    delete[] lua_output->output[i];
  }
  delete[] lua_output->output;
  delete lua_output;
}

LuaEngine* NewLuaEngine() { return new LuaEngine(); }

LuaOutput* ExecuteScript(LuaEngine* l, char* script) {
  LuaOutput* lua_engine_output = new LuaOutput();
  std::vector<std::string> script_execution_output = l->ExecuteScript(script);
  lua_engine_output->size = script_execution_output.size();
  lua_engine_output->output =
      LuaEngine::StringVectorToCharArray(script_execution_output);
  return lua_engine_output;
}
}  // extern "C"
}  // namespace lua_interpreter
