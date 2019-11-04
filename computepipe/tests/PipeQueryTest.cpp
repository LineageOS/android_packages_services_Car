/*
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
#include <hidl/Status.h>
#include <stdio.h>

#include <list>
#include <memory>
#include <string>
#include <utility>

#include "FakeRunner.h"
#include "PipeClient.h"
#include "PipeQuery.h"

using namespace android::automotive::computepipe::router;
using namespace android::automotive::computepipe::router::V1_0::implementation;
using namespace android::automotive::computepipe::tests;
using namespace android::automotive::computepipe::runner::V1_0;
using namespace android::automotive::computepipe::registry::V1_0;
using namespace android::automotive::computepipe::V1_0;
using namespace ::testing;

/**
 * Fakeclass to instantiate client info for query purposes
 */
class FakeClientInfo : public IClientInfo {
  public:
    ::android::hardware::Return<uint32_t> getClientId() override {
        return 1;
    }
};

/**
 * Class that exposes protected interfaces of PipeRegistry
 * a) Used to retrieve entries without client ref counts
 * b) Used to remove entries
 */
class FakeRegistry : public PipeRegistry<IPipeRunner> {
  public:
    std ::unique_ptr<PipeHandle<IPipeRunner>> getDebuggerPipeHandle(const std::string& name) {
        return getPipeHandle(name, nullptr);
    }
    Error RemoveEntry(const std::string& name) {
        return DeletePipeHandle(name);
    }
};

/**
 * Test Fixture class that is responsible for maintaining a registry.
 * The registry object is used to test the query interfaces
 */
class PipeQueryTest : public ::testing::Test {
  protected:
    /**
     * Setup for the test fixture to initialize a registry to be used in all
     * tests
     */
    void SetUp() override {
        mRegistry = std::make_shared<FakeRegistry>();
    }
    /**
     * Utility to generate fake runners
     */
    void addFakeRunner(const std::string& name, const android::wp<IPipeRunner>& runnerIface) {
        std::unique_ptr<PipeHandle<IPipeRunner>> handle(new PipeHandle<IPipeRunner>(runnerIface));
        Error status = mRegistry->RegisterPipe(std::move(handle), name);
        ASSERT_THAT(status, testing::Eq(Error::OK));
    }
    /**
     * Utility to remove runners from the registry
     */
    bool removeRunner(const std::string& name) {
        return mRegistry->RemoveEntry(name) == Error::OK;
    }
    /**
     * Tear down to cleanup registry resources
     */
    void TearDown() override {
        mRegistry = nullptr;
    }
    std::shared_ptr<FakeRegistry> mRegistry;
};

// Check retrieval of inserted entries
TEST_F(PipeQueryTest, GetGraphListTest) {
    sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);
    sp<IPipeRunner> dummy2 = new FakeRunner();
    addFakeRunner("dummy2", dummy1);

    hidl_vec<hidl_string> names;
    auto hidl_cb = [&names](const hidl_vec<hidl_string>& pipeList) { names = pipeList; };
    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    qIface->getGraphList(hidl_cb);
    EXPECT_THAT(names.find("dummy1"), testing::Ne(names.end()));
    EXPECT_THAT(names.find("dummy2"), testing::Ne(names.end()));
}

// Check successful retrieval of runner
TEST_F(PipeQueryTest, GetRunnerTest) {
    sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);

    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    sp<IClientInfo> info = new FakeClientInfo();
    sp<IPipeRunner> runner = qIface->getPipeRunner("dummy1", info);
    EXPECT_THAT(runner, testing::NotNull());
}

// Check retrieval of dead runner
TEST_F(PipeQueryTest, DeadRunnerTest) {
    sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);

    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    dummy1.clear();
    sp<IClientInfo> info = new FakeClientInfo();
    sp<IPipeRunner> runner = qIface->getPipeRunner("dummy1", info);
    EXPECT_THAT(runner, testing::IsNull());
}
