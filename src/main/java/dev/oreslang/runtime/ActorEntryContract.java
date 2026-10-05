package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Exact public ABI contract for a persistent actor hot-load entry.
 *
 * <p>The VM treats the actor implementation as opaque. Admission depends only
 * on the explicit entry identity, actor isolation domain, constructor boundary,
 * Actor&lt;Message, Reply, Error&gt; protocol, and the one public receive ingress.
 * Private fields/helpers/control flow are intentionally outside the ABI. The resulting digest is also used by HotReloadManager to prevent an active actor code unit from silently changing ABI across generations.</p>
 */
public record ActorEntryContract(
        String actorName,
        Ast.ActorKind actorKind,
        List<Parameter> constructorParameters,
        Ast.TypeRef messageType,
        Ast.TypeRef replyType,
        Ast.TypeRef errorType,
        boolean structuralMessage) {

    public record Parameter(Ast.TypeRef type, boolean structural, boolean mutable) {
        public Parameter {
            Objects.requireNonNull(type, "type");
        }

        public static Parameter of(Ast.TypeRef type) {
            return new Parameter(type, false, false);
        }
    }

    public ActorEntryContract {
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(constructorParameters, "constructorParameters");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(replyType, "replyType");
        Objects.requireNonNull(errorType, "errorType");
        if (actorName.isBlank()) {
            throw new IllegalArgumentException("actor entry name cannot be blank");
        }
        if (actorKind == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "actor entry contract requires an actor isolation kind");
        }
        constructorParameters = List.copyOf(constructorParameters);
    }

    public static ActorEntryContract of(
            String actorName,
            Ast.ActorKind actorKind,
            List<Ast.TypeRef> constructorTypes,
            Ast.TypeRef messageType,
            Ast.TypeRef replyType,
            Ast.TypeRef errorType) {
        return new ActorEntryContract(
                actorName,
                actorKind,
                constructorTypes.stream().map(Parameter::of).toList(),
                messageType,
                replyType,
                errorType,
                false);
    }

    /**
     * Verify the entry without executing guest code or allocating a generation.
     */
    public Verification verify(Ast.Program program, Ast.EntryExportDecl entry) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(entry, "entry");
        if (!entry.name().equals(actorName)) {
            throw new IllegalArgumentException(
                    "actor hot-load contract names '" + actorName
                            + "' but code unit exports entry '" + entry.name() + "'");
        }

        Ast.ClassDecl actor = findRootActor(program);
        if (actor.actorKind() != actorKind) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' isolation mismatch: expected "
                            + actorKind + ", found " + actor.actorKind());
        }
        if (actor.isAbstract()) {
            throw new IllegalArgumentException(
                    "hot-loaded actor entry '" + actorName + "' cannot be abstract");
        }
        if (!actor.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "hot-loaded actor entry '" + actorName
                            + "' cannot have unresolved class generic parameters");
        }

        List<Ast.TypeRef> protocol = actor.actorProtocolTypes();
        List<Ast.TypeRef> expectedProtocol =
                List.of(messageType, replyType, errorType);
        if (!protocol.equals(expectedProtocol)) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName
                            + "' must explicitly declare Actor<Message, Reply, Error> ABI "
                            + renderProtocol(expectedProtocol)
                            + "; found " + renderProtocol(protocol));
        }

        verifyConstructor(actor);
        verifyReceive(actor);

        return new Verification(actorName, actorKind, abiDigest());
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
        StringBuilder out = new StringBuilder("ores-persistent-actor-entry-abi-v3\n");
        out.append("actor=").append(actorName).append('\n');
        out.append("isolation=").append(actorKind).append('\n');
        out.append("protocol=")
                .append(typeRef(messageType)).append(',')
                .append(typeRef(replyType)).append(',')
                .append(typeRef(errorType)).append('\n');
        for (Parameter parameter : constructorParameters) {
            out.append("ctor=")
                    .append(parameter.structural()).append(':')
                    .append(parameter.mutable()).append(':')
                    .append(typeRef(parameter.type())).append('\n');
        }
        out.append("receive=")
                .append(structuralMessage).append(":false:")
                .append(typeRef(messageType))
                .append("->void\n");
        return out.toString();
    }

    private Ast.ClassDecl findRootActor(Ast.Program program) {
        Ast.ClassDecl found = null;
        for (Ast.ModuleDecl module : program.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl candidate)
                        || !candidate.name().equals(actorName)) {
                    continue;
                }
                if (found != null) {
                    throw new IllegalArgumentException(
                            "actor hot-load entry '" + actorName + "' is ambiguous");
                }
                found = candidate;
            }
        }
        if (found == null || found.actorKind() == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "hot-load entry '" + actorName
                            + "' must name one top-level persistent actor class");
        }
        return found;
    }

    private void verifyConstructor(Ast.ClassDecl actor) {
        List<Ast.MethodDecl> constructors = actor.methods().stream()
                .filter(method -> method.name().equals("constructor"))
                .toList();
        if (constructors.size() > 1) {
            throw new IllegalArgumentException(
                    "hot-loaded actor '" + actorName
                            + "' must expose at most one runtime constructor ABI");
        }

        List<Ast.Param> actual = constructors.isEmpty()
                ? List.of()
                : constructors.getFirst().parameters();
        if (constructors.isEmpty() && !constructorParameters.isEmpty()) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName
                            + "' constructor ABI mismatch: expected "
                            + constructorParameters.size() + " parameters, found implicit zero-arg constructor");
        }
        if (!constructors.isEmpty()) {
            Ast.MethodDecl ctor = constructors.getFirst();
            if (ctor.visibility() == Ast.Visibility.PUBLIC
                    || ctor.isStatic()
                    || ctor.isAbstract()
                    || ctor.async()
                    || !ctor.genericParameters().isEmpty()
                    || ctor.explicitReceiverType() != null
                    || ctor.returnType() == null
                    || !"void".equals(ctor.returnType().name())
                    || !ctor.returnType().arguments().isEmpty()
                    || ctor.returnType().inferArguments()) {
                throw new IllegalArgumentException(
                        "actor entry '" + actorName
                                + "' constructor must be private/runtime-only, concrete, synchronous, non-generic, and void");
            }
        }

        verifyParameters("constructor", actual, constructorParameters);
    }

    private void verifyReceive(Ast.ClassDecl actor) {
        List<Ast.MethodDecl> publicMethods = actor.methods().stream()
                .filter(method -> !method.isStatic()
                        && method.visibility() == Ast.Visibility.PUBLIC
                        && !method.name().equals("constructor"))
                .toList();
        if (publicMethods.size() != 1
                || !publicMethods.getFirst().name().equals("receive")) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName
                            + "' must expose exactly one public receive(Message): void ingress");
        }

        Ast.MethodDecl receive = publicMethods.getFirst();
        if (receive.isStatic()
                || receive.isAbstract()
                || receive.async()
                || !receive.genericParameters().isEmpty()
                || receive.explicitReceiverType() != null
                || receive.parameters().size() != 1
                || receive.returnType() == null
                || !"void".equals(receive.returnType().name())
                || !receive.returnType().arguments().isEmpty()
                || receive.returnType().inferArguments()) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName
                            + "' receive ABI must be concrete receive(Message): void");
        }

        Ast.Param message = receive.parameters().getFirst();
        if (message.mutable()
                || message.structural() != structuralMessage
                || !Objects.equals(message.type(), messageType)) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' receive ABI mismatch: expected "
                            + renderParameter(new Parameter(
                                    messageType,
                                    structuralMessage,
                                    false))
                            + ", found "
                            + renderParameter(new Parameter(
                                    message.type(),
                                    message.structural(),
                                    message.mutable())));
        }
    }

    private void verifyParameters(
            String label,
            List<Ast.Param> actual,
            List<Parameter> expected) {
        if (actual.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' " + label
                            + " ABI mismatch: expected " + expected.size()
                            + " parameters, found " + actual.size());
        }
        for (int i = 0; i < expected.size(); i++) {
            Parameter want = expected.get(i);
            Ast.Param got = actual.get(i);
            if (!Objects.equals(want.type(), got.type())
                    || want.structural() != got.structural()
                    || want.mutable() != got.mutable()) {
                throw new IllegalArgumentException(
                        "actor entry '" + actorName + "' " + label
                                + " parameter " + (i + 1)
                                + " ABI mismatch: expected "
                                + renderParameter(want) + ", found "
                                + renderParameter(new Parameter(
                                        got.type(),
                                        got.structural(),
                                        got.mutable())));
            }
        }
    }

    private static String renderProtocol(List<Ast.TypeRef> types) {
        if (types.isEmpty()) return "<none>";
        List<String> rendered = new ArrayList<>(types.size());
        for (Ast.TypeRef type : types) rendered.add(typeRef(type));
        return "<" + String.join(",", rendered) + ">";
    }

    private static String renderParameter(Parameter parameter) {
        return (parameter.structural() ? "structural:" : "nominal:")
                + (parameter.mutable() ? "mut:" : "readonly:")
                + typeRef(parameter.type());
    }

    /**
     * Keep the spelling aligned with IncrementalCompiler's public ABI renderer.
     */
    private static String typeRef(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(ref.name());
        if (ref.inferArguments()) return out.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            out.append('<');
            for (Ast.TypeRef arg : ref.arguments()) {
                out.append(typeRef(arg)).append(',');
            }
            out.append('>');
        }
        return out.toString();
    }

    public record Verification(
            String actorName,
            Ast.ActorKind actorKind,
            String abiDigest) { }
}
