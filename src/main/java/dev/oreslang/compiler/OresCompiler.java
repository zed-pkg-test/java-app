package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        return parseAndTypeCheck(source, Set.of(), Map.of());
    }

    public static Ast.Program parseAndTypeCheck(
            String source,
            Set<String> importedAsyncCallables) {
        return parseAndTypeCheck(source, importedAsyncCallables, Map.of());
    }

    public static Ast.Program parseAndTypeCheck(
            String source,
            Set<String> importedAsyncCallables,
            Map<String, List<Boolean>> importedParameterMutability) {
        return analyze(
                Parser.parse(source),
                importedAsyncCallables,
                importedParameterMutability);
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        return analyze(program, Set.of(), Map.of());
    }

    public static Ast.Program analyze(
            Ast.Program program,
            Set<String> importedAsyncCallables) {
        return analyze(program, importedAsyncCallables, Map.of());
    }

    public static Ast.Program analyze(
            Ast.Program program,
            Set<String> importedAsyncCallables,
            Map<String, List<Boolean>> importedParameterMutability) {
        program = TypeChecker.checkTypes(program, importedAsyncCallables);
        OwnershipChecker.check(program, importedParameterMutability);
        return program;
    }

    /**
     * Parse and type-check the complete source first, then perform closed-world
     * build optimization. Type errors in code that later becomes unreachable
     * are still reported; tree shaking is an optimization, not conditional
     * compilation that hides invalid source.
     */
    public static TreeShaker.Result compileForBuild(String source, BuildOptions options) {
        return TreeShaker.shake(parseAndTypeCheck(source), options);
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        Ast.Program program = parseAndTypeCheck(source);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
