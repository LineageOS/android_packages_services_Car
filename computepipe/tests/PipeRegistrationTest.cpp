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
#include <stdio.h>

#include <list>
#include <memory>
#include <string>
#include <utility>

#include "FakeRunner.h"
#include "PipeRegistration.h"

using namespace android::automotive::computepipe::router;
using namespace android::automotive::computepipe::router::V1_0::implementation;
using namespace android::automotive::computepipe::tests;
using namespace android::automotive::computepipe::runner::V1_0;
using namespace android::automotive::computepipe::registry::V1_0;
using namespace android::automotive::computepipe::V1_0;

/**
 * Test fixture that manages the underlying registry creation and tear down
 */
class PipeRegistrationTest : public ::testing::Test {
  protected:
    void SetUp() override {
        mRegistry = std::make_shared<PipeRegistry<IPipeRunner>>();
        ASSERT_THAT(mRegistry, testing::NotNull());
    }

    void TearDown() override {
        mRegistry = nullptr;
    }
    std::shared_ptr<PipeRegistry<IPipeRunner>> mRegistry;
};

// Valid registration succeeds
TEST_F(PipeRegistrationTest, RegisterFakeRunner) {
    sp<IPipeRunner> dummy = new FakeRunner();
    std::unique_ptr<IPipeRegistration> rIface(new PipeRegistration(this->mRegistry));
    EXPECT_THAT(rIface->registerPipeRunner("dummy", dummy), testing::Eq(PipeStatus::OK));
}

// Duplicate registration fails
TEST_F(PipeRegistrationTest, RegisterDuplicateRunner) {
    sp<IPipeRunner> dummy = new FakeRunner();
    std::unique_ptr<IPipeRegistration> rIface(new PipeRegistration(this->mRegistry));
    ASSERT_THAT(rIface->registerPipeRunner("dummy", dummy), testing::Eq(PipeStatus::OK));
    EXPECT_THAT(rIface->registerPipeRunner("dummy", dummy), testing::Eq(PipeStatus::INTERNAL_ERR));
}

// Reregistration of dead runner succeeds
TEST_F(PipeRegistrationTest, RegisterDeadRunner) {
    sp<IPipeRunner> dummy = new FakeRunner();
    std::unique_ptr<IPipeRegistration> rIface(new PipeRegistration(this->mRegistry));
    ASSERT_THAT(rIface->registerPipeRunner("dummy", dummy), testing::Eq(PipeStatus::OK));
    dummy.clear();
    dummy = new FakeRunner();
    EXPECT_THAT(rIface->registerPipeRunner("dummy", dummy), testing::Eq(PipeStatus::OK));
}
