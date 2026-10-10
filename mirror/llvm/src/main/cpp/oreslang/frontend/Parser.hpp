#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace oreslang {

// Deliberately restricted, typed-i64 expression AST. Not the full Java Ast.Program.
struct Expression {
    enum class Kind { Integer, Variable, Negate, Add, Subtract, Multiply, Call };
    Kind kind;
    std::size_t offset = 0;
    std::int64_t integer = 0;
    std::string name;
    std::unique_ptr<Expression> left;
    std::unique_ptr<Expression> right;
    std::vector<std::unique_ptr<Expression>> arguments;
};

struct Function {
    std::string name;
    std::vector<std::string> parameters;
    std::unique_ptr<Expression> result;
    std::size_t offset = 0;
    bool exported = false;
};

struct Program {
    std::string module;
    std::vector<Function> functions;
};

// Parse and statically validate a strict Java-compatible subset: int functions,
// int parameters, return expressions and same-module calls. Reject everything else.
Program parse_program(std::string_view source);

} // namespace oreslang
