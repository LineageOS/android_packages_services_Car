
#include "LooperWrapper.h"
#include "packages/services/Car/cpp/native_telemetry/proto/telemetry.pb.h"

#include <android/native/telemetry/BnNativeTelemetryService.h>
#include <android/native/telemetry/INativeTelemetryReportListener.h>
#include <android/native/telemetry/INativeTelemetryReportReadyListener.h>
#include <utils/Log.h>

#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::native::telemetry::INativeTelemetryReportListener;
using ::android::native::telemetry::INativeTelemetryReportReadyListener;
using ::android::native::telemetry::INativeTelemetryService;

class NativeTelemetryServer {
public:
    explicit NativeTelemetryServer(LooperWrapper* looper);

    void addMetricsConfig(const ::android::String16& metricsConfigName,
                          const std::vector<uint8_t>& metricConfig);

    void setReportReadyListener(
            const ::android::sp<
                    ::android::native::telemetry::INativeTelemetryReportReadyListener>&
                    listener);

    void removeMetricsConfig(const ::android::String16& metricConfigName);

    void removeAllMetricsConfigs();

    void getFinishedReport(const ::android::String16& metricConfigName,
                           const std::shared_ptr<INativeTelemetryReportListener>& listener);

    void clearReportReadyListener();

private:
    class MessageHandlerImpl : public MessageHandler {
    public:
        explicit MessageHandlerImpl(NativeTelemetryServer* server);

        void handleMessage(const Message& message) override;

    private:
        NativeTelemetryServer* mNativeTelemetryServer;  // not owned
    };

private:
    std::mutex mMutex;

    LooperWrapper* mLooper;
    android::sp<MessageHandlerImpl> mMessageHandler;

    std::unordered_map<std::string, android::native::telemetry::MetricsConfig>
            mActiveConfigs;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
