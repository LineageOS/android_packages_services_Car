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

#include "../lua_engine.h"

#include <gtest/gtest.h>

#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace lua_interpreter {
namespace tests {

// The fixture for testing class LuaEngine
class LuaEngineTest : public testing::Test {
 protected:
  lua_interpreter::LuaEngine lua_engine_;

  std::string ConvertVectorToString(std::vector<std::string> vector) {
    std::stringstream output;
    for (std::string s : vector) {
      output << s;
    }
    return output.str();
  }
  std::string ConvertArrayToString(char** array, int size) {
    std::stringstream output;
    for (int i = 0; i < size; i++) {
        output << array[i];
    }
    return output.str();
  }
};

TEST_F(LuaEngineTest, ExecuteScriptEmptyScriptSendsNoOutput) {
  std::vector<std::string> output = lua_engine_.ExecuteScript("");
  EXPECT_EQ(0, output.size());
}

TEST_F(LuaEngineTest, ExecuteScriptNoExplicitReturnSendsNoOutput) {
  std::vector<std::string> output =
      lua_engine_.ExecuteScript("function two() return 2 end");
  EXPECT_EQ(0, output.size());
}

TEST_F(LuaEngineTest, ExecuteScriptSyntaxError) {
  std::vector<std::string> output = lua_engine_.ExecuteScript("f");
  std::string actual = ConvertVectorToString(output);
  EXPECT_TRUE(actual.find("Error encountered while loading the script.") !=
              std::string::npos);
}

TEST_F(LuaEngineTest, ExecuteScriptRuntimeError) {
  std::vector<std::string> output = lua_engine_.ExecuteScript(
      "function add(a, b) return a + b end return add(10)");
  std::string actual = ConvertVectorToString(output);
  EXPECT_TRUE(actual.find("Error encountered while running the script.") !=
              std::string::npos);
}

TEST_F(LuaEngineTest, ExecuteScriptReturnsOutput) {
  std::vector<std::string> output = lua_engine_.ExecuteScript(
      "function add(a, b) return a + b end return add(10, 5)");
  std::string actual = ConvertVectorToString(output);
  EXPECT_EQ("15\t\n", actual);
}

TEST_F(LuaEngineTest, StringVectorToArrayEmpty) {
  std::vector<std::string> vector = {};
  char** array = LuaEngine::StringVectorToCharArray(vector);
  EXPECT_EQ(nullptr, array);
}

TEST_F(LuaEngineTest, StringVectorToArrayNonEmpty) {
  std::vector<std::string> vector = {"1", "2", "3", "4"};
  char** array = LuaEngine::StringVectorToCharArray(vector);
  EXPECT_EQ("1234", ConvertArrayToString(array, 4));
}
}  // namespace tests
}  // namespace lua_interpreter

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
