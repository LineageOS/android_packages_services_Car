#include "SemanticManager.h"

#include <cstdlib>

#include "types/Status.h"

namespace android {
namespace automotive {
namespace computepipe {
namespace runner {
namespace stream_manager {

proto::PacketType SemanticHandle::getType() {
    return mType;
}

uint64_t SemanticHandle::getTimeStamp() {
    return mTimestamp;
}

uint32_t SemanticHandle::getSize() {
    return mSize;
}

const char* SemanticHandle::getData() {
    return mData;
}

native_handle_t SemanticHandle::getNativeHandle() {
    native_handle_t temp;
    temp.numFds = 0;
    temp.numInts = 0;
    return temp;
}

Status SemanticHandle::setMemInfo(const char* data, uint32_t size, uint64_t timestamp,
                                  const proto::PacketType& type) {
    if (data == nullptr || size == 0 || size > kMaxSemanticDataSize) {
        return INVALID_ARGUMENT;
    }
    mData = (char*)malloc(size);
    if (!mData) {
        return NO_MEMORY;
    }
    memcpy(mData, data, size);
    mType = type;
    mTimestamp = timestamp;
    mSize = size;
    return SUCCESS;
}

/* Destroy local copy */
SemanticHandle::~SemanticHandle() {
    free(mData);
}

Status SemanticManager::setIpcDispatchCallback(
    std::function<Status(const std::shared_ptr<MemHandle>)>& cb) {
    mDispatchCallback = cb;
    std::lock_guard<std::mutex> lock(mStateLock);
    mState = RESET;
    return SUCCESS;
}

// TODO: b/146495240 Add support for batching
Status SemanticManager::setMaxInFlightPackets(uint32_t /* maxPackets */) {
    if (!mDispatchCallback) {
        return ILLEGAL_STATE;
    }
    mState = CONFIG_DONE;
    return SUCCESS;
}

Status SemanticManager::start() {
    std::lock_guard<std::mutex> lock(mStateLock);
    if (mState != CONFIG_DONE) {
        return ILLEGAL_STATE;
    }
    mState = RUNNING;
    return SUCCESS;
}

Status SemanticManager::stop(bool /* flush */) {
    std::lock_guard<std::mutex> lock(mStateLock);
    if (mState != RUNNING) {
        return ILLEGAL_STATE;
    }
    /*
     * We skip directly to config_done as there is no outstanding cleanup
     * required
     */
    mState = CONFIG_DONE;
    return SUCCESS;
}

Status SemanticManager::cleanup() {
    std::lock_guard<std::mutex> lock(mStateLock);
    mState = RESET;
    return SUCCESS;
}

Status SemanticManager::freePacket(const std::shared_ptr<MemHandle>& /* handle */) {
    return SUCCESS;
}

Status SemanticManager::queuePacket(const char* data, const uint32_t size, uint64_t timestamp) {
    std::lock_guard<std::mutex> lock(mStateLock);
    // We drop the packet since we have received the stop notifications.
    if (mState != RUNNING) {
        return SUCCESS;
    }
    // Invalid state.
    if (mDispatchCallback == nullptr) {
        return INTERNAL_ERROR;
    }
    auto memHandle = std::make_shared<SemanticHandle>();
    auto status = memHandle->setMemInfo(data, size, timestamp, mType);
    if (status != SUCCESS) {
        return status;
    }
    mDispatchCallback(memHandle);
    return SUCCESS;
}

SemanticManager::SemanticManager(std::string name, const proto::PacketType& type)
    : StreamManager(name, type) {
}

}  // namespace stream_manager
}  // namespace runner
}  // namespace computepipe
}  // namespace automotive
}  // namespace android
