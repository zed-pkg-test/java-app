cmake_minimum_required(VERSION 3.20)
project(oreslang_llvm VERSION 0.2.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(LLVM CONFIG REQUIRED)
if(LLVM_VERSION_MAJOR LESS 14)
    message(FATAL_ERROR "Oreslang requires LLVM 14 or newer; found ${LLVM_PACKAGE_VERSION}")
endif()
message(STATUS "Building Oreslang LLVM against LLVM ${LLVM_PACKAGE_VERSION}")

add_executable(oreslang-llvmc
    src/main/cpp/oreslang/main.cpp
    src/main/cpp/oreslang/frontend/Parser.cpp
    src/main/cpp/oreslang/backend/LlvmCodegen.cpp
)

target_include_directories(oreslang-llvmc PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
    "${LLVM_INCLUDE_DIRS}"
)
add_definitions(${LLVM_DEFINITIONS})
llvm_map_components_to_libnames(ORE_LLVM_LIBS core support)
target_link_libraries(oreslang-llvmc PRIVATE ${ORE_LLVM_LIBS})

include(CTest)
if(BUILD_TESTING)
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    find_program(ORE_CLANG NAMES clang clang-19 clang-18 clang-17 clang-16 clang-15 clang-14)
    function(oreslang_contract_test name fixture)
        add_test(NAME ${name}
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/contract.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/${fixture}"
                ${ARGN})
    endfunction()

    oreslang_contract_test(constant_main constant-main.ores --ir "ret i64 42")
    oreslang_contract_test(module_main module-main.ores --ir "demo.main")
    oreslang_contract_test(call_and_forward_ref forward-call-main.ores --ir "call i64 @add")
    oreslang_contract_test(arithmetic_precedence return-arithmetic.ores --ir "ret i64 28")
    oreslang_contract_test(negative_literal negative-literal.ores --ir "ret i64 42")
    oreslang_contract_test(nested_comments nested-comments.ores --ir "ret i64 42")
    oreslang_contract_test(reject_actor unsupported-actor.ores --diagnostic "expected 'module'")
    # Ownership remains Java-only. Reject all source ownership forms before
    # LLVM IR emission until a lifetime/provenance-aware typechecker exists.
    oreslang_contract_test(reject_rt_borrow unsupported-rt-borrow.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_borrowed_param unsupported-borrowed-parameter.ores --diagnostic "expected 'int'")
    oreslang_contract_test(reject_rt_borrow_mut unsupported-rt-borrow-mut.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_rt_take unsupported-rt-take.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_rt_copy unsupported-rt-copy.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_rt_share unsupported-rt-share.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_unqualified_borrow_call unsupported-borrow-function.ores --diagnostic "unknown function or wrong call arity")
    oreslang_contract_test(reject_static_select unsupported-static-select.ores --diagnostic "expected 'return'")
    oreslang_contract_test(reject_dynamic_select_plan unsupported-dynamic-select-plan.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_undefined undefined-name.ores --diagnostic "unknown parameter")
    # LLVM has no native I/O binding registry or continuation lowering yet.
    # Both spellings must fail closed with no IR until implementation lands.
    oreslang_contract_test(reject_unsupported_native_io_blocking unsupported-native-io-blocking.ores --diagnostic "unknown function or wrong call arity")
    oreslang_contract_test(reject_unsupported_native_io_bare unsupported-native-io-bare.ores --diagnostic "unknown function or wrong call arity")
    oreslang_contract_test(reject_wrong_arity wrong-arity.ores --diagnostic "wrong call arity")
    oreslang_contract_test(reject_duplicates duplicate-function.ores --diagnostic "duplicate function")
    oreslang_contract_test(reject_duplicate_parameters duplicate-parameter.ores --diagnostic "duplicate parameter")
    oreslang_contract_test(reject_oversized_integer overflow-integer.ores --diagnostic "overflows signed 64-bit")
    oreslang_contract_test(reject_unclosed_comment unclosed-comment.ores --diagnostic "unterminated block comment")

    if(ORE_CLANG)
        oreslang_contract_test(native_exec_constant constant-main.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_forward_call forward-call-main.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_arithmetic return-arithmetic.ores --run 28 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_negative negative-literal.ores --run 42 --clang "${ORE_CLANG}")
    else()
        message(WARNING "Clang unavailable: native executable tests are disabled")
    endif()

    # Linux-specific actor-neutral wake/park primitive. This does NOT
    # implement the actor scheduler or certify lock-free mailbox delivery.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        find_package(Threads REQUIRED)
        add_executable(oreslang-parking-signal-test
            tests/parking_signal_test.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-parking-signal-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-parking-signal-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-parking-signal-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME parking_signal_wake_cancel_races COMMAND oreslang-parking-signal-test)
        set_tests_properties(parking_signal_wake_cancel_races PROPERTIES TIMEOUT 25)

        add_executable(oreslang-hungry-carrier-test
            tests/hungry_carrier_test.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-hungry-carrier-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-hungry-carrier-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-hungry-carrier-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME hungry_carrier_async_bounded_cancel
                 COMMAND oreslang-hungry-carrier-test)
        set_tests_properties(hungry_carrier_async_bounded_cancel PROPERTIES TIMEOUT 30)

        # Real native carrier admissions and handler observations are used to
        # produce an executable trace (not fabricated actor-model fixtures).
        add_executable(oreslang-actor-trace-test
            tests/actor_trace_integration.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-actor-trace-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-actor-trace-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-actor-trace-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_actor_ingress_handler_trace
                 COMMAND oreslang-actor-trace-test)
        set_tests_properties(native_actor_ingress_handler_trace PROPERTIES TIMEOUT 15)
    endif()
endif()
