package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Exact public ABI contract for hot-loaded actor code.
 *
 * <p>The runtime deliberately treats the admitted implementation as opaque.
 * Private helpers, classes, control flow, and object layout are not part of this
 * contract. A generation is admissible only when exactly one public actor
 * callable matches every ABI-relevant field below.
 */
public record ActorHotLoadContract(
        String entrypoint,
        Isolation isolation,
        Ast.CallableKind callableKind,
        boolean async,
        boolean nonLexical,
        int genericArity,
        List<Parameter> parameters,
        Ast.TypeRef returnType) {

    public enum Isolation {
        ISOACTOR(Ast.ActorKind.PRIVATE),
        UNTRUSTED(Ast.ActorKind.UNTRUSTED);

        private final Ast.ActorKind actorKind;

        Isolation(Ast.ActorKind actorKind) {
            this.actorKind = actorKind;
        }

        Ast.ActorKind actorKind() {
            return actorKind;
        }
    }

    public record Parameter(Ast.TypeRef type, boolean structural, boolean mutable) {
        public Parameter {
            Objects.requireNonNull(type, "type");
        }

        public static Parameter of(Ast.TypeRef type) {
            return new Parameter(type, false, false);
        }
    }

    public ActorHotLoadContract {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(callableKind, "callableKind");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(returnType, "returnType");
        if (entrypoint.isBlank()) {
            throw new IllegalArgumentException("actor hot-load entrypoint cannot be blank");
        }
        if (genericArity < 0) {
            throw new IllegalArgumentException("actor hot-load generic arity cannot be negative");
        }
        parameters = List.copyOf(parameters);
    }

    public static ActorHotLoadContract isoactorFnc(
            String entrypoint,
            List<Ast.TypeRef> parameterTypes,
            Ast.TypeRef returnType) {
        return new ActorHotLoadContract(
                entrypoint,
                Isolation.ISOACTOR,
                Ast.CallableKind.FNC,
                false,
                false,
                0,
                parameterTypes.stream().map(Parameter::of).toList(),
                returnType);
    }

    public static ActorHotLoadContract untrustedFnc(
            String entrypoint,
            List<Ast.TypeRef> parameterTypes,
            Ast.TypeRef returnType) {
        return new ActorHotLoadContract(
                entrypoint,
                Isolation.UNTRUSTED,
                Ast.CallableKind.FNC,
                false,
                false,
                0,
                parameterTypes.stream().map(Parameter::of).toList(),
                returnType);
    }

    /**
     * Verifies the public actor boundary without depending on implementation
     * structure. The verifier intentionally ignores private declarations.
     */
    public Verification verify(Ast.Program program) {
        Objects.requireNonNull(program, "program");

        List<Candidate> named = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.visibility() == Ast.Visibility.PUBLIC
                        && function.name().equals(entrypoint)) {
                    named.add(new Candidate(module.name(), function));
                }
            }
        }

        if (named.isEmpty()) {
            throw new IllegalArgumentException(
                    "hot-loaded actor does not export required public entrypoint '" + entrypoint + "'");
        }

        List<Candidate> exact = named.stream()
                .filter(candidate -> matches(candidate.function()))
                .toList();

        if (exact.size() != 1) {
            throw new IllegalArgumentException(
                    "hot-loaded actor entrypoint '" + entrypoint
                            + "' does not match required ABI " + canonicalSignature()
                            + "; candidates=" + named.stream()
                                    .map(candidate -> render(candidate.function()))
                                    .toList());
        }

        Candidate admitted = exact.getFirst();
        return new Verification(admitted.moduleName(), entrypoint, abiDigest());
    }

    public String abiDigest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalSignature().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String canonicalSignature() {
        StringBuilder out = new StringBuilder("ores-actor-hotload-abi-v1\n");
        out.append("entrypoint=").append(entrypoint).append('\n');
        out.append("isolation=").append(isolation).append('\n');
        out.append("callable=").append(callableKind).append('\n');
        out.append("async=").append(async).append('\n');
        out.append("non_lexical=").append(nonLexical).append('\n');
        out.append("generic_arity=").append(genericArity).append('\n');
        for (Parameter parameter : parameters) {
            out.append("param=")
                    .append(parameter.structural()).append(':')
                    .append(parameter.mutable()).append(':')
                    .append(typeRef(parameter.type()))
                    .append('\n');
        }
        out.append("return=").append(typeRef(returnType)).append('\n');
        return out.toString();
    }

    private boolean matches(Ast.FunctionDecl function) {
        if (function.actorKind() != isolation.actorKind()) return false;
        if (function.kind() != callableKind) return false;
        if (function.async() != async) return false;
        if (function.nonLexical() != nonLexical) return false;
        if (function.genericParameters().size() != genericArity) return false;
        if (!Objects.equals(function.returnType(), returnType)) return false;
        if (function.parameters().size() != parameters.size()) return false;

        for (int i = 0; i < parameters.size(); i++) {
            Parameter expected = parameters.get(i);
            Ast.Param actual = function.parameters().get(i);
            if (!Objects.equals(actual.type(), expected.type())) return false;
            if (actual.structural() != expected.structural()) return false;
            if (actual.mutable() != expected.mutable()) return false;
        }
        return true;
    }

    private static String render(Ast.FunctionDecl function) {
        StringBuilder out = new StringBuilder();
        out.append(function.visibility()).append(' ')
                .append(function.actorKind()).append(' ')
                .append(function.kind()).append(' ')
                .append(function.name()).append('(');
        for (int i = 0; i < function.parameters().size(); i++) {
            if (i > 0) out.append(',');
            Ast.Param parameter = function.parameters().get(i);
            if (parameter.structural()) out.append("structural ");
            if (parameter.mutable()) out.append("mut ");
            out.append(typeRef(parameter.type()));
        }
        return out.append(") => ").append(typeRef(function.returnType())).toString();
    }

    private static String typeRef(Ast.TypeRef type) {
        if (type == null) return "<inferred>";
        if (type.isStringLiteral()) return "'" + type.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(type.name());
        if (type.inferArguments()) return out.append("<>").toString();
        if (!type.arguments().isEmpty()) {
            out.append('<');
            for (int i = 0; i < type.arguments().size(); i++) {
                if (i > 0) out.append(',');
                out.append(typeRef(type.arguments().get(i)));
            }
            out.append('>');
        }
        return out.toString();
    }

    private record Candidate(String moduleName, Ast.FunctionDecl function) { }

    public record Verification(String moduleName, String entrypoint, String abiDigest) { }
}
