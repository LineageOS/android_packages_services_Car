/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "car-bugreportd"

#include <android-base/errors.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/macros.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <cutils/sockets.h>
#include <errno.h>
#include <fcntl.h>
#include <log/log_main.h>
#include <private/android_filesystem_config.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <chrono>
#include <string>
#include <vector>

namespace {
// Socket to write the progress information.
constexpr const char* kCarBrProgressSocket = "car_br_progress_socket";
// Socket to write the zipped bugreport file.
constexpr const char* kCarBrOutputSocket = "car_br_output_socket";
// The prefix used by bugreportz protocol to indicate bugreport finished successfully.
constexpr const char* kOkPrefix = "OK:";
// Number of connect attempts to dumpstate socket
constexpr const int kMaxDumpstateConnectAttempts = 20;
// Wait time between connect attempts
constexpr const int kWaitTimeBetweenConnectAttemptsInSec = 1;
// Wait time for dumpstate. No timeout in dumpstate is longer than 60 seconds. Choose
// a value that is twice longer.
constexpr const int kDumpstateTimeoutInSec = 120;

// Returns a valid socket descriptor or -1 on failure.
int openSocket(const char* service) {
    int s = android_get_control_socket(service);
    if (s < 0) {
        ALOGE("android_get_control_socket(%s): %s", service, strerror(errno));
        return -1;
    }
    fcntl(s, F_SETFD, FD_CLOEXEC);
    if (listen(s, 4) < 0) {
        ALOGE("listen(control socket): %s", strerror(errno));
        return -1;
    }

    struct sockaddr addr;
    socklen_t alen = sizeof(addr);
    int fd = accept(s, &addr, &alen);
    if (fd < 0) {
        ALOGE("accept(control socket): %s", strerror(errno));
        return -1;
    }
    return fd;
}

// Processes the given dumpstate progress protocol |line| and updates
// |out_last_nonempty_line| when |line| is non-empty, and |out_zip_path| when
// the bugreport is finished.
void processLine(const std::string& line, std::string* out_zip_path,
                 std::string* out_last_nonempty_line) {
    // The protocol is documented in frameworks/native/cmds/bugreportz/readme.md
    if (line.empty()) {
        return;
    }
    *out_last_nonempty_line = line;
    if (line.find(kOkPrefix) != 0) {
        return;
    }
    *out_zip_path = line.substr(strlen(kOkPrefix));
    return;
}

int copyTo(int fd_in, int fd_out, void* buffer, size_t buffer_len) {
    ssize_t bytes_read = TEMP_FAILURE_RETRY(read(fd_in, buffer, buffer_len));
    if (bytes_read == 0) {
        return 0;
    }
    if (bytes_read == -1) {
        // EAGAIN really means time out, so make that clear.
        if (errno == EAGAIN) {
            ALOGE("read timed out");
        } else {
            ALOGE("read terminated abnormally (%s)", strerror(errno));
        }
        return -1;
    }
    // copy all bytes to the output socket
    if (!android::base::WriteFully(fd_out, buffer, bytes_read)) {
        ALOGE("write failed");
        return -1;
    }
    return bytes_read;
}

bool copyFile(const std::string& zip_path, int output_socket) {
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(open(zip_path.c_str(), O_RDONLY)));
    if (fd == -1) {
        return false;
    }
    while (1) {
        char buffer[65536];
        int bytes_copied = copyTo(fd, output_socket, buffer, sizeof(buffer));
        if (bytes_copied == 0) {
            break;
        }
        if (bytes_copied == -1) {
            return false;
        }
    }
    return true;
}

// Triggers a bugreport and waits until it is all collected.
// returns false if error, true if success
bool doBugreport(int progress_socket, size_t* out_bytes_written, std::string* zip_path) {
    // Socket will not be available until service starts.
    android::base::unique_fd s;
    for (int i = 0; i < kMaxDumpstateConnectAttempts; i++) {
        s.reset(socket_local_client("dumpstate", ANDROID_SOCKET_NAMESPACE_RESERVED, SOCK_STREAM));
        if (s != -1) break;
        sleep(kWaitTimeBetweenConnectAttemptsInSec);
    }

    if (s == -1) {
        ALOGE("failed to connect to dumpstatez service");
        return false;
    }

    // Set a timeout so that if nothing is read by the timeout, stop reading and quit
    struct timeval tv = {
        .tv_sec = kDumpstateTimeoutInSec,
        .tv_usec = 0,
    };
    if (setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) != 0) {
        ALOGW("Cannot set socket timeout (%s)", strerror(errno));
    }

    std::string line;
    std::string last_nonempty_line;
    while (true) {
        char buffer[65536];
        ssize_t bytes_read = copyTo(s, progress_socket, buffer, sizeof(buffer));
        if (bytes_read == 0) {
            break;
        }
        if (bytes_read == -1) {
            return false;
        }
        // Process the buffer line by line. this is needed for the filename.
        for (int i = 0; i < bytes_read; i++) {
            char c = buffer[i];
            if (c == '\n') {
                processLine(line, zip_path, &last_nonempty_line);
                line.clear();
            } else {
                line.append(1, c);
            }
        }
    }
    s.reset();
    // Process final line, in case it didn't finish with newline.
    processLine(line, zip_path, &last_nonempty_line);
    // if doBugReport finished successfully, zip path should be set.
    if (zip_path->empty()) {
        ALOGE("no zip file path was found in bugreportz progress data");
        return false;
    }
    return true;
}

// Removes bugreport
void cleanupBugreportFile(const std::string& zip_path) {
    if (unlink(zip_path.c_str()) != 0) {
        ALOGE("Could not unlink %s (%s)", zip_path.c_str(), strerror(errno));
    }
}

}  // namespace

int main(void) {
    ALOGE("Starting bugreport collecting service");

    auto t0 = std::chrono::steady_clock::now();

    // Start the dumpstatez service.
    android::base::SetProperty("ctl.start", "car-dumpstatez");

    size_t bytes_written = 0;

    std::string zip_path;
    int progress_socket = openSocket(kCarBrProgressSocket);
    if (progress_socket < 0) {
        // early out. in this case we will not print the final message, but that is ok.
        android::base::SetProperty("ctl.stop", "car-dumpstatez");
        return EXIT_FAILURE;
    }
    bool ret_val = doBugreport(progress_socket, &bytes_written, &zip_path);
    close(progress_socket);

    int output_socket = openSocket(kCarBrOutputSocket);
    if (output_socket != -1 && ret_val) {
        ret_val = copyFile(zip_path, output_socket);
    }
    if (output_socket != -1) {
        close(output_socket);
    }

    auto delta = std::chrono::duration_cast<std::chrono::duration<double>>(
                     std::chrono::steady_clock::now() - t0)
                     .count();

    std::string result = ret_val ? "success" : "failed";
    ALOGI("bugreport %s in %.02fs, %zu bytes written", result.c_str(), delta, bytes_written);
    cleanupBugreportFile(zip_path);

    // No matter how doBugreport() finished, let's try to explicitly stop
    // car-dumpstatez in case it stalled.
    android::base::SetProperty("ctl.stop", "car-dumpstatez");

    return ret_val ? EXIT_SUCCESS : EXIT_FAILURE;
}
