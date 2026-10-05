package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        EcsEffectAnalyzer.analyze(program);
        return program;
    }

    /** Extract validated ECS system read/write metadata from an already checked program. */
    public static java.util.Map<String, EcsEffectAnalyzer.SystemEffects> ecsEffects(Ast.Program program) {
        return EcsEffectAnalyzer.analyze(program);
    }

    /** Compute deterministic non-conflicting ECS system batches for scheduler planning. */
    public static java.util.List<java.util.List<EcsEffectAnalyzer.SystemEffects>> ecsParallelBatches(Ast.Program program) {
        return EcsEffectAnalyzer.parallelBatches(program);
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
