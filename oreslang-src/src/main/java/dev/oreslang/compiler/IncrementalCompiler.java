package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-granular incremental compiler.
 *
 * Each source file is an independently versioned compilation/code unit.
 * Source edits always rebuild their own unit. Importers rebuild only when the
 * dependency's exported ABI digest changes, so implementation-only edits stay
 * local while public contract changes invalidate the necessary dependents.
 */
public final class IncrementalCompiler {
    private final Map<String, CompiledUnit> cache = new LinkedHashMap<>();

    public synchronized BuildResult compile(Map<String, String> sources) {
        if (sources.isEmpty()) return new BuildResult(Map.of(), Set.of(), Set.of(), List.of());

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = normalizeUnitId(entry.getKey());
            if (normalized.putIfAbsent(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate source unit '" + id + "'");
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> abiHashes = new LinkedHashMap<>();
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();

        // Parse the complete source set before resolving imports. This is the
        // first half of cycle tolerance: A may name B while B names A because
        // neither unit is recursively compiled while discovering the other.
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            hashes.put(entry.getKey(), digest(entry.getValue()));
            Ast.Program program = Parser.parse(entry.getValue());
            parsed.put(entry.getKey(), program);
            abiHashes.put(entry.getKey(), abiDigest(program));
        }

        ImportGraph.validateLinkedImports(parsed);
        Map<String, Set<String>> dependencies =
                ImportGraph.resolveDependencies(parsed, normalized.keySet());
        List<List<String>> initializationGroups = ImportGraph.initializationGroups(dependencies);

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        LinkedHashSet<String> abiChanged = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = cache.get(id);
            boolean sourceChanged = previous == null || !previous.sourceDigest().equals(hashes.get(id));
            boolean depsChanged = previous == null || !previous.dependencies().equals(dependencies.get(id));
            if (sourceChanged || depsChanged) dirty.add(id);
            if (previous == null || !previous.abiDigest().equals(abiHashes.get(id))) abiChanged.add(id);
        }

        /*
         * ABI changes invalidate the full reverse-import closure. This remains
         * conservative until the cross-unit linker tracks exactly which public
         * imported symbols are re-exported by each dependent. Crucially,
         * implementation-only source changes do not enter this queue.
         */
        Map<String, Set<String>> reverse = reverseDependencies(dependencies);
        ArrayDeque<String> abiQueue = new ArrayDeque<>(abiChanged);
        LinkedHashSet<String> abiAffected = new LinkedHashSet<>(abiChanged);
        while (!abiQueue.isEmpty()) {
            String changed = abiQueue.removeFirst();
            for (String dependent : reverse.getOrDefault(changed, Set.of())) {
                dirty.add(dependent);
                if (abiAffected.add(dependent)) abiQueue.addLast(dependent);
            }
        }

        LinkedHashMap<String, CompiledUnit> next = new LinkedHashMap<>();
        LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
        LinkedHashSet<String> reused = new LinkedHashSet<>();

        for (String id : normalized.keySet()) {
            if (!dirty.contains(id) && cache.containsKey(id)) {
                next.put(id, cache.get(id));
                reused.add(id);
                continue;
            }

            /*
             * The normal single-unit checker intentionally never reads another
             * source file. For a linked build, however, actor imports need a
             * type-only ABI view so aliases such as
             *
             *   import actor {Worker as W} from "./workers.ores";
             *   spawn W(...);
             *
             * are checked as actors rather than downgraded to unknown values.
             * Build a synthetic view containing only imported actor ABI stubs:
             * constructor signatures + the one receive(Message): void contract.
             * No foreign implementation body/state is copied or executed.
             */
            Ast.Program linkedCheckView =
                    linkedActorTypeCheckView(id, parsed.get(id), parsed);
            TypeChecker.check(linkedCheckView);
            Ast.Program checked = parsed.get(id);
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id, checked),
                    checked.namespace(),
                    hashes.get(id),
                    abiHashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        cache.keySet().retainAll(normalized.keySet());
        cache.putAll(next);
        return new BuildResult(
                Map.copyOf(next),
                Set.copyOf(rebuilt),
                Set.copyOf(reused),
                initializationGroups);
    }

    private static Ast.Program linkedActorTypeCheckView(
            String unitId,
            Ast.Program program,
            Map<String, Ast.Program> programs) {
        List<Ast.Decl> rootActorStubs = new ArrayList<>();
        List<Ast.ModuleDecl> namespaceStubs = new ArrayList<>();

        for (Ast.ImportDecl imported : program.imports()) {
            String targetId =
                    ImportGraph.resolveImportUnitId(unitId, imported, programs.keySet());
            if (targetId == null) continue; // external package resolver owns it.
            Ast.Program target = programs.get(targetId);
            if (target == null) continue;

            if (imported.kind() == Ast.ImportKind.ACTOR && !imported.wildcard()) {
                for (String sourceName : imported.names()) {
                    Ast.ClassDecl actor = uniqueActorExport(target, sourceName, targetId);
                    rootActorStubs.add(actorImportStub(
                            imported.localName(sourceName), actor, target, targetId));
                }
                continue;
            }

            if ((imported.kind() == Ast.ImportKind.ACTOR
                            || imported.kind() == Ast.ImportKind.ALL)
                    && imported.wildcard()) {
                List<Ast.Decl> declarations = exportedActorStubs(target, targetId);
                if (!declarations.isEmpty()) {
                    namespaceStubs.add(
                            new Ast.ModuleDecl(imported.namespace(), declarations));
                }
                continue;
            }

            if (imported.kind() == Ast.ImportKind.ENTRY) {
                Ast.EntryExportDecl entry = uniqueEntryExport(target, targetId);
                Ast.ClassDecl actor = findActorExport(target, entry.name());
                if (actor != null) {
                    String localName = imported.localName("$entry$");
                    rootActorStubs.add(
                            actorImportStub(localName, actor, target, targetId));
                }
            }
        }

        if (rootActorStubs.isEmpty() && namespaceStubs.isEmpty()) return program;

        List<Ast.ModuleDecl> modules = new ArrayList<>(program.modules().size() + namespaceStubs.size());
        boolean rootSeen = false;
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals(Parser.ROOT_MODULE)) {
                rootSeen = true;
                List<Ast.Decl> declarations =
                        new ArrayList<>(module.declarations().size() + rootActorStubs.size());
                declarations.addAll(module.declarations());
                declarations.addAll(rootActorStubs);
                modules.add(new Ast.ModuleDecl(
                        module.name(), module.annotations(), declarations));
            } else {
                modules.add(module);
            }
        }
        if (!rootSeen && !rootActorStubs.isEmpty()) {
            modules.add(new Ast.ModuleDecl(Parser.ROOT_MODULE, rootActorStubs));
        }
        modules.addAll(namespaceStubs);
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private static List<Ast.Decl> exportedActorStubs(
            Ast.Program target,
            String targetId) {
        LinkedHashMap<String, Ast.ClassDecl> actors = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : target.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.ClassDecl klass)
                        || klass.actorKind() == Ast.ActorKind.NONE) {
                    continue;
                }
                Ast.ClassDecl previous = actors.putIfAbsent(klass.name(), klass);
                if (previous != null && previous != klass) {
                    throw new IllegalArgumentException(
                            "wildcard actor import from '" + targetId
                                    + "' is ambiguous for actor '" + klass.name() + "'");
                }
            }
        }
        List<Ast.Decl> result = new ArrayList<>(actors.size());
        for (Map.Entry<String, Ast.ClassDecl> entry : actors.entrySet()) {
            result.add(actorImportStub(
                    entry.getKey(), entry.getValue(), target, targetId));
        }
        return List.copyOf(result);
    }

    private static Ast.ClassDecl uniqueActorExport(
            Ast.Program target,
            String name,
            String targetId) {
        Ast.ClassDecl actor = findActorExport(target, name);
        if (actor == null) {
            throw new IllegalArgumentException(
                    "import actor '" + name + "' does not resolve in '" + targetId + "'");
        }
        int matches = 0;
        for (Ast.ModuleDecl module : target.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass
                        && klass.actorKind() != Ast.ActorKind.NONE
                        && klass.name().equals(name)) {
                    matches++;
                }
            }
        }
        if (matches != 1) {
            throw new IllegalArgumentException(
                    "import actor '" + name + "' is ambiguous in '" + targetId + "'");
        }
        return actor;
    }

    private static Ast.ClassDecl findActorExport(
            Ast.Program target,
            String name) {
        Ast.ClassDecl found = null;
        for (Ast.ModuleDecl module : target.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.ClassDecl klass)
                        || klass.actorKind() == Ast.ActorKind.NONE
                        || !klass.name().equals(name)) {
                    continue;
                }
                if (found != null) return null;
                found = klass;
            }
        }
        return found;
    }

    private static Ast.EntryExportDecl uniqueEntryExport(
            Ast.Program target,
            String targetId) {
        Ast.EntryExportDecl found = null;
        for (Ast.ModuleDecl module : target.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.EntryExportDecl entry)) continue;
                if (found != null) {
                    throw new IllegalArgumentException(
                            "code unit '" + targetId + "' has multiple entry exports");
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "code unit '" + targetId + "' has no export entry");
        }
        return found;
    }

    private static Ast.ClassDecl actorImportStub(
            String localName,
            Ast.ClassDecl actor,
            Ast.Program target,
            String targetId) {
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("imported actor alias cannot be blank");
        }

        Ast.MethodDecl receive = effectiveReceiveForImport(
                actor, target, new LinkedHashSet<>());
        if (receive == null) {
            if (actor.actorProtocolTypes().size() != 3) {
                throw new IllegalArgumentException(
                        "imported actor '" + actor.name() + "' from '" + targetId
                                + "' has no statically resolvable receive(Message): void ABI");
            }
            receive = syntheticReceive(actor.actorProtocolTypes().getFirst());
        } else {
            receive = signatureOnly(receive);
        }

        List<Ast.MethodDecl> methods = new ArrayList<>();
        for (Ast.MethodDecl method : actor.methods()) {
            if (!method.isStatic() && method.name().equals("constructor")) {
                methods.add(signatureOnly(method));
            }
        }
        methods.add(receive);

        return new Ast.ClassDecl(
                localName,
                false,
                actor.actorKind(),
                actor.genericParameters(),
                List.of(),
                List.of(),
                List.of(),
                methods,
                actor.actorProtocolTypes());
    }

    private static Ast.MethodDecl effectiveReceiveForImport(
            Ast.ClassDecl actor,
            Ast.Program target,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(actor)) {
            throw new IllegalArgumentException(
                    "actor inheritance cycle while resolving imported receive ABI for '"
                            + actor.name() + "'");
        }

        for (Ast.MethodDecl method : actor.methods()) {
            if (!method.isStatic()
                    && method.visibility() == Ast.Visibility.PUBLIC
                    && method.name().equals("receive")
                    && method.arity() == 1) {
                return method;
            }
        }

        for (Ast.TypeRef parentRef : actor.parents()) {
            Ast.ClassDecl parent = findActorExport(target, parentRef.name());
            if (parent == null) continue;
            Ast.MethodDecl inherited =
                    effectiveReceiveForImport(parent, target, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private static Ast.MethodDecl syntheticReceive(Ast.TypeRef messageType) {
        return new Ast.MethodDecl(
                "receive",
                Ast.Visibility.PUBLIC,
                false,
                false,
                false,
                null,
                List.of(),
                List.of(new Ast.Param(messageType, "message", false, false)),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of());
    }

    private static Ast.MethodDecl signatureOnly(Ast.MethodDecl method) {
        return new Ast.MethodDecl(
                method.name(),
                method.visibility(),
                method.isStatic(),
                false,
                false,
                method.explicitReceiverType(),
                method.genericParameters(),
                method.parameters(),
                method.returnType(),
                method.annotations(),
                List.of());
    }

    public synchronized void clear() {
        cache.clear();
    }

    private static String abiDigest(Ast.Program program) {
        StringBuilder abi = new StringBuilder("ores-abi-v1\n");
        abi.append("namespace=").append(program.namespace() == null ? "" : program.namespace()).append('\n');

        for (Ast.ModuleDecl module : program.modules()) {
            abi.append("module ").append(module.name()).append('\n');
            for (Ast.Annotation annotation : module.annotations()) {
                if (annotation.name().equals("AdheresTo")) {
                    abi.append(" module-annotation AdheresTo:");
                    for (Ast.TypeRef arg : annotation.arguments()) abi.append(typeRef(arg)).append(',');
                    abi.append('\n');
                }
            }
            for (Ast.Decl decl : module.declarations()) appendAbi(abi, decl);
        }
        return digest(abi.toString());
    }

    private static void appendAbi(StringBuilder abi, Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(fn.actorKind()).append(' ').append(fn.kind()).append(" pub ").append(fn.name());
            appendGenerics(abi, fn.genericParameters());
            appendParams(abi, fn.parameters());
            abi.append("=>").append(typeRef(fn.returnType())).append('\n');
            return;
        }
        if (decl instanceof Ast.ClassDecl klass) {
            abi.append(klass.actorKind()).append(" class ").append(klass.name());
            appendGenerics(abi, klass.genericParameters());
            if (!klass.actorProtocolTypes().isEmpty()) {
                abi.append(" actor-contract<");
                for (Ast.TypeRef protocolType : klass.actorProtocolTypes()) {
                    abi.append(typeRef(protocolType)).append(',');
                }
                abi.append('>');
            }
            abi.append(" extends ");
            for (Ast.TypeRef parent : klass.parents()) abi.append(typeRef(parent)).append(',');
            abi.append(" implements ");
            for (Ast.TypeRef iface : klass.interfaces()) abi.append(typeRef(iface)).append(',');
            abi.append('\n');
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                abi.append(" field ").append(field.bindingKind()).append(' ')
                        .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                        .append(' ').append(field.name()).append('\n');
            }
            for (Ast.MethodDecl method : klass.methods()) {
                boolean actorConstructor = klass.actorKind() != Ast.ActorKind.NONE
                        && !method.isStatic()
                        && method.name().equals("constructor");
                if (method.visibility() != Ast.Visibility.PUBLIC && !actorConstructor) continue;
                abi.append(actorConstructor
                                ? " actor-constructor "
                                : (method.isStatic() ? " static-fnc " : " method "))
                        .append(method.name());
                appendGenerics(abi, method.genericParameters());
                appendParams(abi, method.parameters());
                abi.append("=>").append(typeRef(method.returnType())).append('\n');
            }
            return;
        }
        if (decl instanceof Ast.EntryExportDecl entry) {
            abi.append("entry ").append(entry.name()).append('\n');
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            abi.append("interface ").append(iface.visibility()).append(' ').append(iface.name());
            appendGenerics(abi, iface.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : iface.parents()) abi.append(typeRef(parent)).append(',');
            abi.append('\n');
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    abi.append(" iface-fnc ").append(fn.name());
                    appendGenerics(abi, fn.genericParameters());
                    appendParams(abi, fn.parameters());
                    abi.append("=>").append(typeRef(fn.returnType())).append('\n');
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    abi.append(" iface-field ").append(field.name()).append(':').append(typeRef(field.type())).append('\n');
                }
            }
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            abi.append("type ").append(alias.name());
            appendGenerics(abi, alias.genericParameters());
            abi.append('=').append(typeRef(alias.target())).append('\n');
            return;
        }
        if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
            abi.append("binding ").append(field.bindingKind()).append(' ')
                    .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                    .append(' ').append(field.name()).append('\n');
        }
    }

    private static void appendGenerics(StringBuilder abi, List<String> generics) {
        abi.append('<');
        for (String generic : generics) abi.append(generic).append(',');
        abi.append('>');
    }

    private static void appendParams(StringBuilder abi, List<Ast.Param> params) {
        abi.append('(');
        for (Ast.Param param : params) {
            if (param.structural()) abi.append("structural ");
            abi.append(typeRef(param.type())).append(',');
        }
        abi.append(')');
    }

    private static String typeRef(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(ref.name());
        if (ref.inferArguments()) return out.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            out.append('<');
            for (Ast.TypeRef arg : ref.arguments()) out.append(typeRef(arg)).append(',');
            out.append('>');
        }
        return out.toString();
    }

    private static Set<String> resolveDependencies(String unitId, Ast.Program program, Set<String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Path parent = Path.of(unitId).getParent();
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!available.contains(candidate) && !candidate.endsWith(".ores") && available.contains(candidate + ".ores")) {
                candidate += ".ores";
            }
            if (available.contains(candidate)) {
                result.add(candidate);
            } else if (raw.startsWith(".")) {
                throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                        + "' does not resolve to a supplied Oreslang source unit");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Set<String>> reverseDependencies(Map<String, Set<String>> dependencies) {
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) reverse.put(id, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private static String packageId(String unitId, Ast.Program program) {
        if (program.namespace() != null) return program.namespace();
        String id = unitId;
        if (id.endsWith(".ores")) id = id.substring(0, id.length() - ".ores".length());
        return id;
    }

    private static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record CompiledUnit(
            String unitId,
            String packageId,
            String namespace,
            String sourceDigest,
            String abiDigest,
            Set<String> dependencies,
            String sourceText,
            Ast.Program program) {
        public CompiledUnit {
            dependencies = Set.copyOf(dependencies);
        }
    }

    public record BuildResult(
            Map<String, CompiledUnit> units,
            Set<String> rebuiltUnits,
            Set<String> reusedUnits,
            List<List<String>> initializationGroups) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
            initializationGroups = initializationGroups.stream()
                    .map(List::copyOf)
                    .toList();
        }

        /** Backward-compatible constructor for callers that do not need lifecycle planning. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits) {
            this(units, rebuiltUnits, reusedUnits, List.of());
        }

        public boolean rebuilt(String unitId) { return rebuiltUnits.contains(normalizeUnitId(unitId)); }
        public boolean reused(String unitId) { return reusedUnits.contains(normalizeUnitId(unitId)); }

        /**
         * Flattens the dependency-first SCC plan. Units in the same inner list
         * form one load barrier: all of them must be linked before the first
         * init hook in that group executes.
         */
        public List<String> initializationOrder() {
            return initializationGroups.stream().flatMap(List::stream).toList();
        }
    }
}
