/**
 * Copyright 2019 The Android Open Source Project
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
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <list>
#include <string>

#include "FakeRunner.h"
#include "Registry.h"

using namespace ::android::automotive::computepipe::router;
using namespace ::android::automotive::computepipe::tests;
using namespace ::testing;

class FakeClient : public ClientHandle {
    uint32_t getClientId() override {
        return 0;
    }
    bool isAlive() override {
        return true;
    }
    ~FakeClient() {
    }
};

/**
 * Test for PipeRegistry::getRunner()
 * Check if the api does not mistakenly increment the refcount
 * Check if the api correctly handles bad client
 * Check if the api correctly handles multiclient error
 * Check if the api correctly handles a deleted runner retrieval
 * Check if registry implementation correctly deletes entry for
 * dead runner
 */
TEST(RegistryTest, GetRunnerTest) {
    PipeRegistry<FakeRunner> registry;
    sp<FakeRunner> runner = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> handle(new PipeHandle<FakeRunner>(runner));
    ASSERT_THAT(runner, testing::NotNull());
    // Verify refcount
    registry.RegisterPipe(std::move(handle), "random");
    EXPECT_THAT(runner->getStrongCount(), Eq(1));
    // Verify bad client
    EXPECT_THAT(registry.getClientPipeHandle("random", nullptr), IsNull());
    // Verify correct retrieval
    std::unique_ptr<ClientHandle> client(new FakeClient());
    ASSERT_THAT(client, NotNull());
    EXPECT_THAT(registry.getClientPipeHandle("random", std::move(client)), NotNull());
    // verify multiclient failure
    client.reset(new FakeClient());
    EXPECT_THAT(registry.getClientPipeHandle("random", std::move(client)), IsNull());
    // Verify deleted runner
    sp<FakeRunner> dummy;
    dummy = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> dummyHandle(new PipeHandle<FakeRunner>(dummy));
    registry.RegisterPipe(std::move(dummyHandle), "dummy");
    dummy.clear();
    client.reset(new FakeClient());
    EXPECT_THAT(registry.getClientPipeHandle("dummy", std::move(client)), IsNull());
}

/**
 * Test for PipeRegistry::getPipeList()
 * Check if the api correctly handles empty db
 */
TEST(RegistryTest, GetPipeListTest) {
    PipeRegistry<FakeRunner> registry;
    // Confirm entry registry
    std::list<std::string> names = registry.getPipeList();
    ASSERT_THAT(names.size(), Eq(0));
    // Confirm 1 entry
    sp<FakeRunner> runner = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> handle(new PipeHandle<FakeRunner>(runner));
    registry.RegisterPipe(std::move(handle), "random");
    names = registry.getPipeList();
    ASSERT_THAT(names.size(), Eq(1));
    ASSERT_STREQ((*names.begin()).c_str(), "random");
}

/**
 * Test for PipeRegistry::registerPipe()
 * Check if the api correctly rejects duplicate entries
 * Check if the api correctly handles reregistration of a deleted runner
 */
TEST(RegistryTest, RegisterPipeTest) {
    PipeRegistry<FakeRunner> registry;
    sp<FakeRunner> runner = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> handle(new PipeHandle<FakeRunner>(runner));
    Error status = registry.RegisterPipe(std::move(handle), "random");
    ASSERT_THAT(status, Eq(OK));
    // Duplicate entry
    status = registry.RegisterPipe(nullptr, "random");
    ASSERT_THAT(status, Eq(DUPLICATE_PIPE));
    // Deleted runner
    runner.clear();
    runner = new FakeRunner();
    handle.reset(new PipeHandle<FakeRunner>(runner));
    status = registry.RegisterPipe(std::move(handle), "random");
    ASSERT_THAT(status, Eq(OK));
}
