#include "oreslang/runtime/ParkingSignal.hpp"
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace std::chrono_literals;
using oreslang::runtime::ParkingSignal;
using oreslang::runtime::WaitResult;

static void require(bool pred, const char* why) {
    if (!pred) throw std::runtime_error(why);
}

static void test_ready_before_wait() {
    ParkingSignal gate;
    std::atomic<bool> ready{false}, stop{false};
    ready.store(true, std::memory_order_release);
    gate.notify();
    require(gate.wait_until([&] {return ready.load(std::memory_order_acquire);}, stop, 1s)
                == WaitResult::ready, "ready-before-park lost event");
}

static void test_spurious_and_delayed_wake() {
    ParkingSignal gate;
    std::atomic<bool> ready{false}, stop{false};
    std::thread producer([&] {
        std::this_thread::sleep_for(10ms);
        gate.notify(); // notification is NOT completion
        std::this_thread::sleep_for(35ms);
        ready.store(true, std::memory_order_release);
        gate.notify();
    });
    const auto start = std::chrono::steady_clock::now();
    const auto status = gate.wait_until([&] {return ready.load(std::memory_order_acquire);}, stop, 1s);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    producer.join();
    require(status == WaitResult::ready, "lost delayed completion");
    require(elapsed >= 25ms, "spurious notification mistaken for completion");
}

static void test_150ms_io_and_thread_identity() {
    ParkingSignal gate;
    std::atomic<bool> ready{false}, stop{false};
    const auto identity = std::this_thread::get_id();
    std::thread io([&] {
        std::this_thread::sleep_for(150ms);
        ready.store(true, std::memory_order_release);
        gate.notify();
    });
    const auto begin = std::chrono::steady_clock::now();
    const auto status = gate.wait_until([&] {return ready.load(std::memory_order_acquire);}, stop, 2s);
    const auto elapsed = std::chrono::steady_clock::now() - begin;
    io.join();
    require(status == WaitResult::ready, "long I/O wait failed");
    require(elapsed >= 100ms, "long I/O wait returned prematurely");
    require(std::this_thread::get_id() == identity, "dedicated carrier switched threads");
    std::cout << "observed 150-ms example wall-ms="
              << std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count() << "\n";
}

static void test_cancel_wakes_parked_thread() {
    ParkingSignal gate;
    std::atomic<bool> stop{false};
    std::atomic<WaitResult> result{WaitResult::timed_out};
    std::thread waiter([&] {
        result.store(gate.wait_until([] {return false;}, stop, 2s), std::memory_order_release);
    });
    std::this_thread::sleep_for(20ms);
    stop.store(true, std::memory_order_release);
    gate.notify();
    waiter.join();
    require(result.load(std::memory_order_acquire) == WaitResult::stopped,
            "cancellation failed to wake sleeping carrier");
}

static void test_shutdown_precedence_and_timeout() {
    ParkingSignal gate;
    std::atomic<bool> stop{true};
    require(gate.wait_until([] {return true;}, stop, 5ms) == WaitResult::stopped,
            "stop must take priority over ready");
    stop.store(false, std::memory_order_release);
    require(gate.wait_until([] {return false;}, stop, 18ms) == WaitResult::timed_out,
            "expected bounded monotonic timeout");
}

static void test_cross_thread_carrier_rejected() {
    ParkingSignal gate;
    std::atomic<bool> stop{false};
    require(gate.wait_until([] {return true;}, stop, 1ms) == WaitResult::ready,
            "first carrier must be able to register");
    std::atomic<bool> rejected{false};
    std::thread intruder([&] {
        try { (void)gate.wait_until([] {return true;}, stop, 1ms); }
        catch (const std::logic_error&) { rejected.store(true, std::memory_order_release); }
    });
    intruder.join();
    require(rejected.load(std::memory_order_acquire),
            "different thread must not acquire dedicated carrier lease");
}

static void test_multiple_notifiers_and_wake_races() {
    ParkingSignal gate;
    std::atomic<int> published{0};
    std::atomic<bool> stop{false};
    constexpr int producers = 4, per_producer = 1500;
    std::vector<std::thread> sources;
    for (int p = 0; p < producers; ++p) sources.emplace_back([&] {
        for (int n = 0; n < per_producer; ++n) {
            published.fetch_add(1, std::memory_order_release);
            gate.notify();
            if ((n & 63) == 0) std::this_thread::yield();
        }
    });
    const auto status = gate.wait_until([&] {
        return published.load(std::memory_order_acquire) == producers * per_producer;
    }, stop, 5s);
    for (auto& t : sources) t.join();
    require(status == WaitResult::ready, "racing notifiers lost wakeup");
    require(published.load(std::memory_order_acquire) == producers * per_producer,
            "some producers failed");
}

int main() {
    try {
        test_ready_before_wait();
        test_spurious_and_delayed_wake();
        test_150ms_io_and_thread_identity();
        test_cancel_wakes_parked_thread();
        test_shutdown_precedence_and_timeout();
        test_cross_thread_carrier_rejected();
        for (int i = 0; i < 15; ++i) test_multiple_notifiers_and_wake_races();
        std::cout << "PASS: Linux parking signal tests\n";
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return EXIT_FAILURE;
    }
}
