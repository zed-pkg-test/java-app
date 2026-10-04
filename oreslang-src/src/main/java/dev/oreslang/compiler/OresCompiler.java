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
     * Oreslang guest-code compilation strategy.
     *
     * <p>This is intentionally separate from how the OresVM host is deployed.
     * A Native Image containing the interpreter is not the same thing as an
     * AOT-compiled Oreslang application.
     */
    public enum CompilationMode {
        INTERPRETED,
        JIT,
        AOT
    }

    /**
     * Build/link-time declaration categories shared by JIT and AOT backends.
     *
     * <p>STRUCT and TRAIT are reserved in the manifest model now so the
     * four-way type stack can land without changing the closed-world contract.
     */
    public enum DeclarationKind {
        MODULE,
        NAMESPACE,
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

    /** One statically declared build/link symbol. */
    public record DeclarationSymbol(String qualifiedName, DeclarationKind kind) {
        public DeclarationSymbol {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                throw new IllegalArgumentException("declaration qualifiedName cannot be blank");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Deterministic closed-world declaration inventory. */
    public record DeclarationManifest(List<DeclarationSymbol> symbols) {
        public DeclarationManifest {
            symbols = List.copyOf(symbols);
        }

        public boolean contains(String qualifiedName, DeclarationKind kind) {
            return symbols.contains(new DeclarationSymbol(qualifiedName, kind));
        }
    }

    /**
     * Front-end artifact shared by interpreter, JIT and future native AOT
     * code-generation backends.
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

    /**
     * Validate one source unit for a selected Oreslang guest compilation mode.
     *
     * <p>All modes consume the same static declaration/type graph. Runtime
     * instances/data remain dynamic; runtime namespace/module/type definition
     * does not.
     */
    public static CompilationUnit validateForCompilation(
            String source,
            IsolatePolicy policy,
            CompilationMode mode) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(mode, "mode");

        Ast.Program program = validateForIsolate(source, policy);
        DeclarationManifest manifest = declarationManifest(program);

        if (mode == CompilationMode.AOT) {
            requireAotCompatible(program, manifest);
        }

        return new CompilationUnit(program, mode, manifest);
    }

    public static CompilationUnit validateForAot(
            String source,
            IsolatePolicy policy) {
        return validateForCompilation(source, policy, CompilationMode.AOT);
    }

    /**
     * Inventory all static declarations after parsing/type checking.
     *
     * <p>The AST is deliberately declaration-oriented: named containers and
     * type definitions are not statements or expressions, so ordinary runtime
     * control flow cannot manufacture them.
     */
    public static DeclarationManifest declarationManifest(Ast.Program program) {
        Objects.requireNonNull(program, "program");

        List<DeclarationSymbol> symbols = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();

        for (Ast.ModuleDecl container : program.modules()) {
            boolean root = Parser.ROOT_MODULE.equals(container.name());
            String prefix = root ? "" : container.name();

            if (!root) {
                addSymbol(
                        symbols,
                        identities,
                        container.name(),
                        container.isNamespace()
                                ? DeclarationKind.NAMESPACE
                                : DeclarationKind.MODULE);
            }

            for (Ast.Decl declaration : container.declarations()) {
                collectDeclaration(symbols, identities, prefix, declaration);
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
            addSymbol(
                    symbols,
                    identities,
                    qualify(prefix, fn.name()),
                    DeclarationKind.FUNCTION);
            return;
        }

        if (declaration instanceof Ast.ClassDecl klass) {
            String className = qualify(prefix, klass.name());
            addSymbol(symbols, identities, className, DeclarationKind.CLASS);

            for (Ast.FieldDecl field : klass.fields()) {
                addSymbol(
                        symbols,
                        identities,
                        qualify(className, field.name()),
                        DeclarationKind.CLASS_FIELD);
            }

            for (Ast.MethodDecl method : klass.methods()) {
                addSymbol(
                        symbols,
                        identities,
                        qualify(className, method.name()),
                        DeclarationKind.CLASS_METHOD);
            }
            return;
        }

        if (declaration instanceof Ast.InterfaceDecl iface) {
            String interfaceName = qualify(prefix, iface.name());
            addSymbol(
                    symbols,
                    identities,
                    interfaceName,
                    DeclarationKind.INTERFACE);

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
            addSymbol(
                    symbols,
                    identities,
                    qualify(prefix, alias.name()),
                    DeclarationKind.TYPE_ALIAS);
            return;
        }

        if (declaration instanceof Ast.FieldDecl field) {
            addSymbol(
                    symbols,
                    identities,
                    qualify(prefix, field.name()),
                    DeclarationKind.MODULE_BINDING);
            return;
        }

        throw new IllegalStateException(
                "unhandled static declaration node: "
                        + declaration.getClass().getName());
    }

    private static void requireAotCompatible(
            Ast.Program program,
            DeclarationManifest manifest) {

        for (Ast.ModuleDecl container : program.modules()) {
            for (Ast.Decl declaration : container.declarations()) {
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

        if (manifest.symbols().isEmpty()) {
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
                    "duplicate static declaration in compilation manifest: "
                            + qualifiedName + " (" + kind + ")");
        }
        symbols.add(new DeclarationSymbol(qualifiedName, kind));
    }

    private static String qualify(String prefix, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("declaration name cannot be blank");
        }
        return prefix == null || prefix.isBlank() ? name : prefix + "." + name;
    }
}
