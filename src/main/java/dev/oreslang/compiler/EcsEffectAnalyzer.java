package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and extracts Oreslang ECS/data-oriented effect metadata.
 *
 * <p>The language remains object/actor capable; only declarations explicitly
 * marked as components/systems participate in this model. The compiler can use
 * these read/write sets as the same dependency facts consumed by task,
 * vectorization, and heterogeneous-placement planning.</p>
 */
public final class EcsEffectAnalyzer {
    private static final Set<String> EFFECT_ANNOTATIONS =
            Set.of("system", "reads", "writes", "optional", "without", "gpu_eligible");

    private EcsEffectAnalyzer() { }

    public record SystemEffects(
            String qualifiedName,
            Set<String> reads,
            Set<String> writes,
            Set<String> optional,
            Set<String> without,
            boolean gpuEligible,
            boolean structuralCommands) {

        public SystemEffects {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
            optional = Set.copyOf(optional);
            without = Set.copyOf(without);
        }

        public Set<String> required() {
            LinkedHashSet<String> result = new LinkedHashSet<>(reads);
            result.addAll(writes);
            return Set.copyOf(result);
        }

        public boolean conflictsWith(SystemEffects other) {
            LinkedHashSet<String> otherTouched = new LinkedHashSet<>(other.reads);
            otherTouched.addAll(other.writes);
            otherTouched.addAll(other.optional);
            if (!Collections.disjoint(writes, otherTouched)) return true;

            LinkedHashSet<String> thisTouched = new LinkedHashSet<>(reads);
            thisTouched.addAll(writes);
            thisTouched.addAll(optional);
            return !Collections.disjoint(other.writes, thisTouched);
        }
    }

    /**
     * Validates component POD rules and system query/effect declarations and
     * returns deterministic metadata keyed by module-qualified function name.
     */
    public static Map<String, SystemEffects> analyze(Ast.Program program) {
        Index index = new Index(program);
        index.validateComponents();

        LinkedHashMap<String, SystemEffects> result = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl function)) continue;
                SystemEffects effects = index.systemEffects(module.name(), function);
                if (effects != null) result.put(effects.qualifiedName(), effects);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Deterministic dependency levels. Systems within one returned batch have
     * no component write/read-or-write conflicts and are eligible to run in
     * parallel (subject to the normal OresVM scheduler/placement policy).
     */
    public static List<List<SystemEffects>> parallelBatches(Ast.Program program) {
        List<SystemEffects> systems = List.copyOf(analyze(program).values());
        int[] levels = new int[systems.size()];
        int max = -1;
        for (int i = 0; i < systems.size(); i++) {
            int level = 0;
            for (int j = 0; j < i; j++) {
                if (systems.get(i).conflictsWith(systems.get(j))) {
                    level = Math.max(level, levels[j] + 1);
                }
            }
            levels[i] = level;
            max = Math.max(max, level);
        }

        List<List<SystemEffects>> batches = new ArrayList<>();
        for (int level = 0; level <= max; level++) {
            List<SystemEffects> batch = new ArrayList<>();
            for (int i = 0; i < systems.size(); i++) {
                if (levels[i] == level) batch.add(systems.get(i));
            }
            batches.add(List.copyOf(batch));
        }
        return List.copyOf(batches);
    }

    private static final class Index {
        private final Map<String, Ast.ComponentDecl> components = new LinkedHashMap<>();
        private final Map<Ast.ComponentDecl, String> componentOwners = new java.util.IdentityHashMap<>();
        private final Set<String> ambiguousComponents = new LinkedHashSet<>();
        private final Map<String, Ast.TypeAliasDecl> aliases = new LinkedHashMap<>();

        private Index(Ast.Program program) {
            for (Ast.ModuleDecl module : program.modules()) {
                LinkedHashSet<String> localTypes = new LinkedHashSet<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass) {
                        claimTypeName(localTypes, module.name(), klass.name(), "class");
                    } else if (declaration instanceof Ast.InterfaceDecl iface) {
                        claimTypeName(localTypes, module.name(), iface.name(), "interface");
                    } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                        claimTypeName(localTypes, module.name(), alias.name(), "type alias");
                        aliases.put(module.name() + "." + alias.name(), alias);
                        aliases.putIfAbsent(alias.name(), alias);
                    } else if (declaration instanceof Ast.ComponentDecl component) {
                        claimTypeName(localTypes, module.name(), component.name(), "component");
                        putComponent(module.name(), component);
                    }
                }
            }
        }

        private static void claimTypeName(
                Set<String> names,
                String module,
                String name,
                String kind) {
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "duplicate type name '" + module + "." + name
                                + "'; component/class/interface/type-alias names share one type namespace");
            }
        }

        private void putComponent(String module, Ast.ComponentDecl component) {
            String qualified = qualify(module, component.name());
            if (components.putIfAbsent(qualified, component) != null) {
                throw new IllegalArgumentException("duplicate component '" + qualified + "'");
            }
            componentOwners.put(component, module);
            Ast.ComponentDecl previous = components.putIfAbsent(component.name(), component);
            if (previous != null && previous != component) {
                ambiguousComponents.add(component.name());
                components.remove(component.name());
            }
        }

        private void validateComponents() {
            for (Ast.ComponentDecl component : componentOwners.keySet()) {
                LinkedHashSet<String> fields = new LinkedHashSet<>();
                for (Ast.FieldDecl field : component.fields()) {
                    if (!fields.add(field.name())) {
                        throw new IllegalArgumentException(
                                "duplicate component field '" + component.name() + "." + field.name() + "'");
                    }
                    if (field.type() == null) {
                        throw new IllegalArgumentException(
                                "component field '" + component.name() + "." + field.name()
                                        + "' requires an explicit type");
                    }
                    if (field.initializer() != null) {
                        throw new IllegalArgumentException(
                                "component field '" + component.name() + "." + field.name()
                                        + "' cannot have an initializer");
                    }
                    requirePod(field.type(), component, new LinkedHashSet<>());
                }
            }
        }

        private void requirePod(
                Ast.TypeRef type,
                Ast.ComponentDecl owner,
                Set<Ast.ComponentDecl> stack) {
            if (type.isBorrow()) {
                throw nonPod(owner, type, "borrowed references are not component POD");
            }
            if (type.isUnion()) {
                throw nonPod(owner, type, "union layout is not yet a fixed ECS ABI");
            }
            if (type.isTupleType()) {
                for (Ast.TypeRef element : type.arguments()) requirePod(element, owner, stack);
                return;
            }
            if (type.isRecordType()) {
                for (Ast.TypeRef member : type.recordMembers().values()) requirePod(member, owner, stack);
                return;
            }
            if (type.inferArguments() || !type.arguments().isEmpty()) {
                throw nonPod(owner, type, "generic/container types are not component POD in this ABI");
            }

            if (switch (type.name()) {
                case "i8", "i16", "i32", "i64",
                     "u8", "u16", "u32", "u64",
                     "int", "uint",
                     "f32", "f64", "float",
                     "complex64", "complex128", "complex",
                     "bool", "Bool", "Entity" -> true;
                default -> false;
            }) {
                return;
            }

            Ast.ComponentDecl nested = findComponent(type.name());
            if (nested != null) {
                if (!stack.add(nested)) {
                    throw nonPod(owner, type, "recursive component layout is not permitted");
                }
                for (Ast.FieldDecl field : nested.fields()) requirePod(field.type(), owner, stack);
                stack.remove(nested);
                return;
            }

            Ast.TypeAliasDecl alias = findAlias(type.name(), owner);
            if (alias != null) {
                if (!alias.genericParameters().isEmpty()) {
                    throw nonPod(owner, type, "generic aliases are not component POD in this ABI");
                }
                requirePod(alias.target(), owner, stack);
                return;
            }

            throw nonPod(
                    owner,
                    type,
                    "components may contain fixed-layout scalars, Entity, tuples/records of POD, or other POD components");
        }

        private IllegalArgumentException nonPod(
                Ast.ComponentDecl owner,
                Ast.TypeRef type,
                String reason) {
            return new IllegalArgumentException(
                    "component '" + qualifiedComponent(owner) + "' has non-POD field type '"
                            + display(type) + "': " + reason);
        }

        private SystemEffects systemEffects(String module, Ast.FunctionDecl function) {
            List<Ast.Annotation> ecsAnnotations = function.annotations().stream()
                    .filter(annotation -> EFFECT_ANNOTATIONS.contains(annotation.name()))
                    .toList();
            if (ecsAnnotations.isEmpty()) return null;

            Map<String, Ast.Annotation> byName = new LinkedHashMap<>();
            for (Ast.Annotation annotation : ecsAnnotations) {
                if (byName.putIfAbsent(annotation.name(), annotation) != null) {
                    throw new IllegalArgumentException(
                            "duplicate @" + annotation.name() + " on " + qualify(module, function.name()));
                }
            }

            Ast.Annotation system = byName.get("system");
            if (system == null) {
                throw new IllegalArgumentException(
                        "ECS effect annotations require @system on " + qualify(module, function.name()));
            }
            requireNoArguments(system, function);

            if (function.kind() != Ast.CallableKind.FNC
                    || function.actorKind() != Ast.ActorKind.NONE
                    || function.async()
                    || function.nonLexical()
                    || !function.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "@system '" + qualify(module, function.name())
                                + "' must be a synchronous non-actor non-generic fnc");
            }
            if (!isSimple(function.returnType(), "void")) {
                throw new IllegalArgumentException(
                        "@system '" + qualify(module, function.name()) + "' must return void");
            }

            LinkedHashSet<String> queryComponents = new LinkedHashSet<>();
            boolean querySeen = false;
            boolean commandsSeen = false;
            for (Ast.Param parameter : function.parameters()) {
                if (parameter.type().name().equals("Query")) {
                    if (querySeen) {
                        throw new IllegalArgumentException(
                                "@system '" + qualify(module, function.name()) + "' may declare only one Query parameter");
                    }
                    querySeen = true;
                    if (parameter.type().inferArguments() || parameter.type().arguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                "Query on @system '" + qualify(module, function.name())
                                        + "' requires explicit component type arguments");
                    }
                    for (Ast.TypeRef componentRef : parameter.type().arguments()) {
                        String component = requireComponentReference(componentRef, module, "Query");
                        if (!queryComponents.add(component)) {
                            throw new IllegalArgumentException(
                                    "duplicate component '" + component + "' in system Query");
                        }
                    }
                    continue;
                }
                if (isSimple(parameter.type(), "Commands")) {
                    if (commandsSeen) {
                        throw new IllegalArgumentException(
                                "@system '" + qualify(module, function.name()) + "' may declare only one Commands parameter");
                    }
                    commandsSeen = true;
                    continue;
                }
                throw new IllegalArgumentException(
                        "@system parameters are restricted to one Query<...> and optional Commands; found '"
                                + display(parameter.type()) + " " + parameter.name() + "'");
            }
            if (!querySeen) {
                throw new IllegalArgumentException(
                        "@system '" + qualify(module, function.name()) + "' requires exactly one Query<...> parameter");
            }

            Set<String> reads = effectSet(byName.get("reads"), module, "reads");
            Set<String> writes = effectSet(byName.get("writes"), module, "writes");
            Set<String> optional = effectSet(byName.get("optional"), module, "optional");
            Set<String> without = effectSet(byName.get("without"), module, "without");

            rejectOverlap(reads, writes, "@reads", "@writes", function, module);
            rejectOverlap(reads, optional, "@reads", "@optional", function, module);
            rejectOverlap(writes, optional, "@writes", "@optional", function, module);

            LinkedHashSet<String> positive = new LinkedHashSet<>(reads);
            positive.addAll(writes);
            positive.addAll(optional);
            rejectOverlap(positive, without, "required/optional access", "@without", function, module);

            if (!positive.equals(queryComponents)) {
                throw new IllegalArgumentException(
                        "@system '" + qualify(module, function.name())
                                + "' Query components must exactly match @reads/@writes/@optional; query="
                                + queryComponents + ", effects=" + positive);
            }

            boolean gpuEligible = byName.containsKey("gpu_eligible");
            if (gpuEligible) {
                requireNoArguments(byName.get("gpu_eligible"), function);
                if (commandsSeen) {
                    throw new IllegalArgumentException(
                            "@gpu_eligible system '" + qualify(module, function.name())
                                    + "' cannot request Commands/structural mutation");
                }
            }

            return new SystemEffects(
                    qualify(module, function.name()),
                    reads,
                    writes,
                    optional,
                    without,
                    gpuEligible,
                    commandsSeen);
        }

        private Set<String> effectSet(Ast.Annotation annotation, String module, String name) {
            if (annotation == null) return Set.of();
            if (annotation.arguments().isEmpty()) {
                throw new IllegalArgumentException("@" + name + " requires at least one component type");
            }
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Ast.TypeRef argument : annotation.arguments()) {
                String component = requireComponentReference(argument, module, "@" + name);
                if (!result.add(component)) {
                    throw new IllegalArgumentException(
                            "duplicate component '" + component + "' in @" + name);
                }
            }
            return Set.copyOf(result);
        }

        private String requireComponentReference(Ast.TypeRef ref, String module, String where) {
            if (ref.inferArguments() || !ref.arguments().isEmpty()
                    || ref.isBorrow() || ref.isUnion() || ref.isTupleType() || ref.isRecordType()) {
                throw new IllegalArgumentException(where + " expects component names, got '" + display(ref) + "'");
            }

            Ast.ComponentDecl component = findComponentInModule(ref.name(), module);
            if (component == null) {
                throw new IllegalArgumentException(
                        where + " references unknown or ambiguous component '" + ref.name()
                                + "'; cross-unit component imports are intentionally not inferred");
            }
            return qualifiedComponent(component);
        }

        private Ast.ComponentDecl findComponentInModule(String name, String module) {
            if (name.contains(".")) return findComponent(name);
            Ast.ComponentDecl local = components.get(qualify(module, name));
            if (local != null) return local;
            return findComponent(name);
        }

        private Ast.ComponentDecl findComponent(String name) {
            if (ambiguousComponents.contains(name)) return null;
            return components.get(name);
        }

        private Ast.TypeAliasDecl findAlias(String name, Ast.ComponentDecl owner) {
            String module = componentOwners.get(owner);
            Ast.TypeAliasDecl local = aliases.get(qualify(module, name));
            return local != null ? local : aliases.get(name);
        }

        private String qualifiedComponent(Ast.ComponentDecl component) {
            return qualify(componentOwners.get(component), component.name());
        }

        private static void requireNoArguments(Ast.Annotation annotation, Ast.FunctionDecl function) {
            if (!annotation.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "@" + annotation.name() + " on " + function.name() + " does not take arguments");
            }
        }

        private static void rejectOverlap(
                Set<String> left,
                Set<String> right,
                String leftName,
                String rightName,
                Ast.FunctionDecl function,
                String module) {
            LinkedHashSet<String> overlap = new LinkedHashSet<>(left);
            overlap.retainAll(right);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "@system '" + qualify(module, function.name()) + "' has conflicting "
                                + leftName + "/" + rightName + " components " + overlap);
            }
        }
    }

    private static boolean isSimple(Ast.TypeRef type, String name) {
        return type != null
                && type.name().equals(name)
                && type.arguments().isEmpty()
                && !type.inferArguments()
                && !type.isBorrow()
                && !type.isUnion()
                && !type.isTupleType()
                && !type.isRecordType();
    }

    private static String display(Ast.TypeRef type) {
        if (type == null) return "<inferred>";
        if (type.arguments().isEmpty()) return type.name();
        return type.name() + "<" + type.arguments().stream().map(EcsEffectAnalyzer::display)
                .reduce((a, b) -> a + ", " + b).orElse("") + ">";
    }

    private static String qualify(String module, String name) {
        return module.equals(Parser.ROOT_MODULE) ? name : module + "." + name;
    }
}
