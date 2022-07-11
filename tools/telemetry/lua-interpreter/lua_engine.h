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

#ifndef TOOLS_TELEMETRY_LUA_INTERPRETER_LUA_ENGINE_H_
#define TOOLS_TELEMETRY_LUA_INTERPRETER_LUA_ENGINE_H_

#include <lua.hpp>
#include <string>
#include <vector>

namespace lua_interpreter {

// Encapsulates Lua script execution environment.
// Example:
//      LuaEngine lua_engine;
//      std::vector<std::string> script_output =
//          lua_engine.ExecuteScript("print('2')")
class LuaEngine {
 public:
  // Creates a new instance of the LuaEngine.
  LuaEngine();
  ~LuaEngine();

  // Loads and invokes the Lua script provided as scriptBody string.
  // Returns the output from executing the given script.
  // If loading or invocation are unsuccessful, the errors are returned
  // in the output.
  std::vector<std::string> ExecuteScript(std::string script_body);

  // Returns an allocated char** pointing to null-terminated equivalents
  // of the strings within the vector passed in.
  // Returns nullptr if the vector contains no elements.
  //
  // There is no std::vector<std::string> in C, so this type must be
  // converted to a type usable by C, hence this utility function.
  static char** StringVectorToCharArray(std::vector<std::string> vector);

 private:
  // Stores the current contents of the stack into output_.
  // This method is invoked by a running Lua script when printing to the
  // console.
  // This method separates each element in the stack with tabs and supplies a
  // newline (same as the print function in the base Lua library).
  // This method returns 0 to indicate that no results were pushed to Lua
  // stack according to Lua C function calling convention. More info:
  // https://www.lua.org/manual/5.4/manual.html#lua_CFunction
  static int DumpStack(lua_State* lua_state);

  // Maintains the state of Lua.
  lua_State* lua_state_;

  // Holds the output of the last script execution.
  static std::vector<std::string> output_;
};

// Adds compatibility with Python.
// Since Python is written in C, the external functions must be C callable (so
// C++ types must be converted, etc).
extern "C" {

// Holds information about the output of the execution.
struct LuaOutput {
  // Holds the output of the script execution.
  char** output;

  // Details how many strings are within output.
  //
  // The output array doesn't have size information attached so
  // the size of the array must be encoded in the struct for iteration (or risk
  // Segmentation Faults from accessing random data).
  int size;
};

// Frees up the memory used by the lua_output.
void FreeLuaOutput(LuaOutput* lua_output);

// Creates a new instance of the LuaEngine.
LuaEngine* NewLuaEngine();

// Loads and invokes the Lua script provided as scriptBody string.
// Allocates and returns the output from executing the given script in the
// form of the LuaOutput struct.
// If loading or invocation are unsuccessful, the errors are returned
// in the output.
LuaOutput* ExecuteScript(LuaEngine* l, char* script);
}  // extern "C"
}  // namespace lua_interpreter

#endif  // TOOLS_TELEMETRY_LUA_INTERPRETER_LUA_ENGINE_H_
