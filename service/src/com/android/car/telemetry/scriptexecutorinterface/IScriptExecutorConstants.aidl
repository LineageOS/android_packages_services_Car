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

package com.android.car.telemetry.scriptexecutorinterface;

// TODO(b/194324369): Investigate if we could combine it
// with IScriptExecutorListener.aidl

interface IScriptExecutorConstants {
  /**
   * Default error type.
   */
  const int ERROR_TYPE_UNSPECIFIED = 0;

  /**
   * Used when an error occurs in the ScriptExecutor code.
   */
  const int ERROR_TYPE_SCRIPT_EXECUTOR_ERROR = 1;

  /**
   * Used when an error occurs while executing the Lua script (such as
   * errors returned by lua_pcall)
   */
  const int ERROR_TYPE_LUA_RUNTIME_ERROR = 2;

  /**
   * Used to log errors by a script itself, for instance, when a script received
   * inputs outside of expected range.
   */
  const int ERROR_TYPE_LUA_SCRIPT_ERROR = 3;
}

