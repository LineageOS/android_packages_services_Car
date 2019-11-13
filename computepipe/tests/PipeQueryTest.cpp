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

#include <android/automotive/computepipe/registry/BnClientInfo.h>
#include <binder/IInterface.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <list>
#include <memory>
#include <string>
#include <utility>

#include "FakeRunner.h"
#include "PipeClient.h"
#include "PipeQuery.h"
#include "PipeRunner.h"

using namespace android::automotive::computepipe::router;
using namespace android::automotive::computepipe::router::V1_0::implementation;
using namespace android::automotive::computepipe::tests;
using namespace android::automotive::computepipe::runner;
using namespace android::automotive::computepipe::registry;
using namespace android::binder;
using namespace ::testing;

/**
 * Fakeclass to instantiate client info for query purposes
 */
class FakeClientInfo : public BnClientInfo {
  public:
    Status getClientId(int32_t* id) override {
        *id = 1;
        return Status::ok();
    }
    android::status_t linkToDeath(const android::sp<DeathRecipient>& recipient,
                                  void* cookie = nullptr, uint32_t flags = 0) override {
        (void)recipient;
        (void)cookie;
        (void)flags;
        return OK;
    };
};

/**
 * Class that exposes protected interfaces of PipeRegistry
 * a) Used to retrieve entries without client ref counts
 * b) Used to remove entries
 */
class FakeRegistry : public PipeRegistry<PipeRunner> {
  public:
    std ::unique_ptr<PipeHandle<PipeRunner>> getDebuggerPipeHandle(const std::string& name) {
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
    void addFakeRunner(const std::string& name, const android::sp<IPipeRunner>& runnerIface) {
        std::unique_ptr<PipeHandle<PipeRunner>> handle = std::make_unique<RunnerHandle>(runnerIface);
        Error status = mRegistry->RegisterPipe(std::move(handle), name);
        ASSERT_THAT(status, testing::Eq(Error::OK));
    }
    /**
     * Utility to remove runners from the registry
     */
    void removeRunner(const std::string& name) {
        ASSERT_THAT(mRegistry->RemoveEntry(name), testing::Eq(Error::OK));
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
    android::sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);
    android::sp<IPipeRunner> dummy2 = new FakeRunner();
    addFakeRunner("dummy2", dummy1);

    std::vector<std::string>* outNames = new std::vector<std::string>();
    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    ASSERT_TRUE(qIface->getGraphList(outNames).isOk());

    ASSERT_NE(outNames->size(), 0);
    EXPECT_THAT(std::find(outNames->begin(), outNames->end(), "dummy1"),
                testing::Ne(outNames->end()));
    EXPECT_THAT(std::find(outNames->begin(), outNames->end(), "dummy2"),
                testing::Ne(outNames->end()));
}

// Check successful retrieval of runner
TEST_F(PipeQueryTest, GetRunnerTest) {
    android::sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);

    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    android::sp<IClientInfo> info = new FakeClientInfo();
    android::sp<IPipeRunner> runner;
    ASSERT_TRUE(qIface->getPipeRunner("dummy1", info, &runner).isOk());
    EXPECT_THAT(runner, testing::NotNull());
}

// Check retrieval of dead runner
TEST_F(PipeQueryTest, DeadRunnerTest) {
    android::sp<IPipeRunner> dummy1 = new FakeRunner();
    addFakeRunner("dummy1", dummy1);

    std::unique_ptr<PipeQuery> qIface = std::make_unique<PipeQuery>(mRegistry);
    dummy1.clear();
    removeRunner("dummy1");
    android::sp<IClientInfo> info = new FakeClientInfo();
    android::sp<IPipeRunner> runner;
    qIface->getPipeRunner("dummy1", info, &runner);
    EXPECT_THAT(runner, testing::IsNull());
}
