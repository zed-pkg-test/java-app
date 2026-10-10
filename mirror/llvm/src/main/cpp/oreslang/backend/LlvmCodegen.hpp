#pragma once

#include "oreslang/frontend/Parser.hpp"
#include <string>

namespace oreslang {

// Construct LLVM IR for the validated i64-only fragment, verify before emitting.
std::string emit_verified_llvm_ir(const Program& source);

} // namespace oreslang
