
#include "NativeTelemetryServer.h"

#include <utils/String16.h>
#include <utils/Vector.h>

#include <string>

namespace android {
namespace automotive {
namespace telemetry {

class NativeTelemetryServiceImpl :
      public android::native::telemetry::BnNativeTelemetryService {
public:
    explicit NativeTelemetryServiceImpl(NativeTelemetryServer* server);

    android::binder::Status setReportReadyListener(
            const ::android::sp<
                    ::android::native::telemetry::INativeTelemetryReportReadyListener>&
                    listener) override;

    android::binder::Status clearReportReadyListener() override;

    android::binder::Status addMetricsConfig(const ::android::String16& metricConfigName,
                                             const ::std::vector<uint8_t>& metricConfig) override;

    android::binder::Status removeMetricsConfig(
            const ::android::String16& metricConfigName) override;

    android::binder::Status removeAllMetricsConfigs() override;

    android::binder::Status getFinishedReport(
            const ::android::String16& metricConfigName,
            const ::android::sp<
                    ::android::native::telemetry::INativeTelemetryReportListener>&
                    listener) override;

private:
    NativeTelemetryServer* mNativeTelemetryServer;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android