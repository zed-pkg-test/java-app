package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    /**
     * Guest-code compilation strategy. This is intentionally separate from how
     * the OresVM/host runtime itself is deployed (JVM vs Native Image).
     */
    public enum CompilationMode {
        INTERPRETED,
        JIT,
        AOT
    }

    /**
     * Closed-world declaration categories understood by the compiler/linker.
     * STRUCT and TRAIT are reserved here now so their eventual syntax follows
     * the same static-declaration contract as classes/interfaces.
     */
    public enum DeclarationKind {
        NAMESPACE,
        MODULE,
        FUNCTION,
        CLASS,
        CLASS_FIELD,
        CLASS_METHOD,
        STRUCT,
        TRAIT,
        INTERFACE,
        INTERFACE_FUNCTION,
        INTERFACE_FIELD,
        TYPE_ALIAS,
        MODULE_BINDING
    }

    /** One build/link-time symbol. Definitions are never created at runtime. */
    public record DeclarationSymbol(String qualifiedName, DeclarationKind kind) {
        public DeclarationSymbol {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                throw new IllegalArgumentException("declaration qualifiedName cannot be blank");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Deterministic inventory used by JIT and AOT backends from the same AST. */
    public record DeclarationManifest(List<DeclarationSymbol> symbols) {
        public DeclarationManifest {
            symbols = List.copyOf(symbols);
        }

        public boolean contains(String qualifiedName, DeclarationKind kind) {
            return symbols.contains(new DeclarationSymbol(qualifiedName, kind));
        }
    }

    /**
     * Front-end result shared by interpreter, JIT and future native AOT codegen.
     * The AOT mode here means the source has passed the closed-world language
     * contract; machine-code emission is a backend step, not this validation.
     */
    public record CompilationUnit(
            Ast.Program program,
            CompilationMode mode,
            DeclarationManifest declarations) {
        public CompilationUnit {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(declarations, "declarations");
        }
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return TypeChecker.check(Parser.parse(source));
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

    /**
     * Validate one source unit for a selected guest compilation strategy.
     *
     * <p>All three modes consume the same declaration/type model. In AOT mode,
     * every namespace/module/type/member definition must be discoverable in the
     * parsed program before code generation begins. Runtime instances remain
     * dynamic; runtime type/namespace/module creation does not.
     */
    public static CompilationUnit validateForCompilation(
            String source,
            IsolatePolicy policy,
            CompilationMode mode) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(mode, "mode");
        Ast.Program program = validateForIsolate(source, policy);
        DeclarationManifest declarations = declarationManifest(program);
        if (mode == CompilationMode.AOT) {
            requireAotCompatible(program, declarations);
        }
        return new CompilationUnit(program, mode, declarations);
    }

    public static CompilationUnit validateForAot(String source, IsolatePolicy policy) {
        return validateForCompilation(source, policy, CompilationMode.AOT);
    }

    /**
     * Builds the closed-world symbol inventory. Parser/AST shape is part of the
     * guarantee: ModuleDecl is not a Stmt or Expr, and ClassDecl/InterfaceDecl
     * are declaration nodes, so no function/loop/branch can manufacture a new
     * namespace, module or type definition at runtime.
     */
    public static DeclarationManifest declarationManifest(Ast.Program program) {
        Objects.requireNonNull(program, "program");

        List<DeclarationSymbol> symbols = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        String namespace = program.namespace();

        if (namespace != null) {
            addSymbol(symbols, identities, namespace, DeclarationKind.NAMESPACE);
        }

        for (Ast.ModuleDecl module : program.modules()) {
            boolean root = Parser.ROOT_MODULE.equals(module.name());
            String modulePrefix = root
                    ? nullToEmpty(namespace)
                    : qualify(namespace, module.name());

            if (!root) {
                addSymbol(symbols, identities, modulePrefix, DeclarationKind.MODULE);
            }

            for (Ast.Decl declaration : module.declarations()) {
                collectDeclaration(symbols, identities, modulePrefix, declaration);
            }
        }

        return new DeclarationManifest(symbols);
    }

    private static void collectDeclaration(
            List<DeclarationSymbol> symbols,
            Set<String> identities,
            String prefix,
            Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            addSymbol(symbols, identities, qualify(prefix, fn.name()), DeclarationKind.FUNCTION);
            return;
        }

        if (declaration instanceof Ast.ClassDecl klass) {
            String className = qualify(prefix, klass.name());
            addSymbol(symbols, identities, className, DeclarationKind.CLASS);
            for (Ast.FieldDecl field : klass.fields()) {
                addSymbol(symbols, identities, qualify(className, field.name()), DeclarationKind.CLASS_FIELD);
            }
            for (Ast.MethodDecl method : klass.methods()) {
                addSymbol(symbols, identities, qualify(className, method.name()), DeclarationKind.CLASS_METHOD);
            }
            return;
        }

        if (declaration instanceof Ast.InterfaceDecl iface) {
            String interfaceName = qualify(prefix, iface.name());
            addSymbol(symbols, identities, interfaceName, DeclarationKind.INTERFACE);
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl function) {
                    addSymbol(
                            symbols,
                            identities,
                            qualify(interfaceName, function.name()),
                            DeclarationKind.INTERFACE_FUNCTION);
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    addSymbol(
                            symbols,
                            identities,
                            qualify(interfaceName, field.name()),
                            DeclarationKind.INTERFACE_FIELD);
                }
            }
            return;
        }

        if (declaration instanceof Ast.TypeAliasDecl alias) {
            addSymbol(symbols, identities, qualify(prefix, alias.name()), DeclarationKind.TYPE_ALIAS);
            return;
        }

        if (declaration instanceof Ast.FieldDecl field) {
            addSymbol(symbols, identities, qualify(prefix, field.name()), DeclarationKind.MODULE_BINDING);
            return;
        }

        throw new IllegalStateException(
                "unhandled static declaration node: " + declaration.getClass().getName());
    }

    private static void requireAotCompatible(
            Ast.Program program,
            DeclarationManifest declarations) {
        // The closed-world rule is structural rather than heuristic. If a future
        // AST revision introduces declaration-valued expressions/statements or
        // runtime type-definition nodes, this check must reject them until an
        // AOT-safe lowering exists.
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl
                        || declaration instanceof Ast.ClassDecl
                        || declaration instanceof Ast.InterfaceDecl
                        || declaration instanceof Ast.TypeAliasDecl
                        || declaration instanceof Ast.FieldDecl)) {
                    throw new IllegalArgumentException(
                            "AOT requires statically discoverable declarations; unsupported node "
                                    + declaration.getClass().getName());
                }
            }
        }

        if (declarations.symbols().isEmpty()) {
            throw new IllegalArgumentException(
                    "AOT compilation requires at least one statically declared symbol");
        }
    }

    private static void addSymbol(
            List<DeclarationSymbol> symbols,
            Set<String> identities,
            String qualifiedName,
            DeclarationKind kind) {
        String identity = kind.name() + ":" + qualifiedName;
        if (!identities.add(identity)) {
            throw new IllegalArgumentException(
                    "duplicate static declaration in compilation manifest: " + qualifiedName
                            + " (" + kind + ")");
        }
        symbols.add(new DeclarationSymbol(qualifiedName, kind));
    }

    private static String qualify(String prefix, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("declaration name cannot be blank");
        }
        return prefix == null || prefix.isBlank() ? name : prefix + "." + name;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
