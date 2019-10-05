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
#include <string>
#include <utility>

#include "FakeRunner.h"
#include "PipeContext.h"

using namespace ::android::automotive::computepipe::router;
using namespace ::android::automotive::computepipe::tests;
using namespace ::testing;

TEST(PipeContextTest, IsAliveTest) {
    sp<FakeRunner> dummy = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> pHandle(new PipeHandle<FakeRunner>(dummy));
    ASSERT_TRUE(pHandle->isAlive());

    PipeContext pContext(std::move(pHandle), "random");
    ASSERT_TRUE(pContext.isAlive());
    dummy.clear();
    ASSERT_FALSE(pContext.isAlive());
}

TEST(PipeContextTest, GetHandleTest) {
    sp<FakeRunner> dummy = new FakeRunner();
    std::unique_ptr<PipeHandle<FakeRunner>> pHandle(new PipeHandle<FakeRunner>(dummy));
    PipeContext pContext(std::move(pHandle), "random");

    std::unique_ptr<PipeHandle<FakeRunner>> dupHandle = pContext.dupPipeHandle();
    sp<FakeRunner> dummy2 = dupHandle->getInterface().promote();
    ASSERT_THAT(dummy2->getStrongCount(), Eq(2));
    dummy2.clear();

    ASSERT_TRUE(dupHandle->isAlive());
    dummy.clear();
    ASSERT_FALSE(dupHandle->isAlive());
    ASSERT_FALSE(pContext.isAlive());
}
