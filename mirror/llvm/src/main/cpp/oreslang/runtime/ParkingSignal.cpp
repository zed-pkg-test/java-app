#include "oreslang/runtime/ParkingSignal.hpp"

#if !defined(__linux__)
#error "ParkingSignal is an explicitly Linux-only experimental component"
#endif

#include <cerrno>
#include <climits>
#include <cstdint>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>

namespace oreslang::runtime {

ParkingSignal::ParkingSignal() {
    fd_ = ::eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (fd_ == -1) throw std::system_error(errno, std::generic_category(), "eventfd");
}

ParkingSignal::~ParkingSignal() noexcept { if (fd_ != -1) ::close(fd_); }

void ParkingSignal::notify() {
    const std::uint64_t one = 1;
    for (;;) {
        const auto written = ::write(fd_, &one, sizeof(one));
        if (written == static_cast<ssize_t>(sizeof(one))) return;
        if (written < 0 && errno == EINTR) continue;
        if (written < 0 && errno == EAGAIN) return; // full = already signaled
        throw std::system_error(written < 0 ? errno : EIO, std::generic_category(), "eventfd write");
    }
}

int ParkingSignal::remaining_ms(std::chrono::steady_clock::time_point deadline) {
    const auto now = std::chrono::steady_clock::now();
    if (now >= deadline) return 0;
    const auto delta = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
    if (delta >= INT_MAX) return INT_MAX;
    return static_cast<int>(delta == 0 ? 1 : delta); // ceil sub-ms remainder
}

void ParkingSignal::wait_for_signal(int timeout_ms) {
    pollfd poller{fd_, POLLIN, 0};
    const int status = ::poll(&poller, 1, timeout_ms);
    if (status == 0) return; // timer expiration; caller rechecks predicates
    if (status < 0) {
        if (errno == EINTR) return;
        throw std::system_error(errno, std::generic_category(), "eventfd poll");
    }
    if ((poller.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
        throw std::system_error(EIO, std::generic_category(), "eventfd poll failure");
    }
    if (!(poller.revents & POLLIN)) return; // defensive spurious event
    std::uint64_t count = 0;
    for (;;) {
        const auto bytes = ::read(fd_, &count, sizeof(count));
        if (bytes == static_cast<ssize_t>(sizeof(count))) return;
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes < 0 && errno == EAGAIN) return; // another signal reader is forbidden, but safe
        throw std::system_error(bytes < 0 ? errno : EIO, std::generic_category(), "eventfd read");
    }
}

}  // namespace oreslang::runtime
