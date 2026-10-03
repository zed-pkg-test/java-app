package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time stateful-trait composition.
 *
 * Traits have no runtime object identity. This pass expands trait state,
 * behavior, and interface obligations into the consuming class before the
 * ordinary type/ownership/capability/runtime passes execute.
 */
public final class TraitComposer {
    private static final String VALIDATION_CLASS_PREFIX = "$trait$";
    private TraitComposer() { }

    public static Ast.Program compose(Ast.Program program) {
        return new Composer(program).compose();
    }

    /**
     * Synthetic abstract classes exist only while the normal type/ownership
     * passes validate trait declarations, including traits that no class uses.
     */
    public static Ast.Program stripValidationClasses(Ast.Program program) {
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> declarations = module.declarations().stream()
                    .filter(declaration -> !(declaration instanceof Ast.ClassDecl klass)
                            || !klass.name().startsWith(VALIDATION_CLASS_PREFIX))
                    .toList();
            modules.add(new Ast.ModuleDecl(
                    module.name(),
                    module.singleton(),
                    module.annotations(),
                    declarations));
        }
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private record TraitBinding(String module, Ast.TraitDecl declaration) { }
    private record FieldEntry(Ast.FieldDecl field, String origin) { }
    private record MethodEntry(Ast.MethodDecl method, String origin) { }

    private static final class Material {
        private final LinkedHashMap<String, FieldEntry> fields = new LinkedHashMap<>();
        private final LinkedHashMap<String, MethodEntry> methods = new LinkedHashMap<>();
        private final LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
    }

    private static final class Composer {
        private final Ast.Program program;
        private final Map<String, TraitBinding> qualifiedTraits = new LinkedHashMap<>();
        private final Map<String, TraitBinding> unqualifiedTraits = new LinkedHashMap<>();
        private final Set<String> ambiguousTraits = new HashSet<>();
        private final Map<String, Material> materialCache = new HashMap<>();

        private Composer(Ast.Program program) {
            this.program = program;
            indexTraits();
        }

        private Ast.Program compose() {
            validateNoTraitInstantiation();
            validateNoTraitRuntimeTypes();
            List<Ast.ModuleDecl> modules = new ArrayList<>();
            for (Ast.ModuleDecl module : program.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.TraitDecl) {
                        continue;
                    }
                    if (declaration instanceof Ast.ClassDecl klass) {
                        declarations.add(composeClass(module.name(), klass));
                    } else {
                        declarations.add(declaration);
                    }
                }
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.TraitDecl trait) {
                        declarations.add(validationClass(module.name(), trait));
                    }
                }
                modules.add(new Ast.ModuleDecl(
                        module.name(),
                        module.singleton(),
                        module.annotations(),
                        declarations));
            }
            return new Ast.Program(program.namespace(), program.imports(), modules);
        }

        private void validateNoTraitRuntimeTypes() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    validateNoTraitRuntimeTypes(module.name(), declaration);
                }
            }
        }

        private void validateNoTraitRuntimeTypes(String moduleName, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                Set<String> generics = Set.copyOf(function.genericParameters());
                validateParams(moduleName, function.parameters(), generics, "function " + function.name());
                validateTypeRef(moduleName, function.returnType(), generics, "function return " + function.name());
                validateNoTraitRuntimeTypes(moduleName, function.body(), generics);
            } else if (declaration instanceof Ast.InitDecl init) {
                validateNoTraitRuntimeTypes(moduleName, init.body(), Set.of());
            } else if (declaration instanceof Ast.FieldDecl field) {
                validateTypeRef(moduleName, field.type(), Set.of(), "module field " + field.name());
                if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), Set.of());
            } else if (declaration instanceof Ast.ClassDecl klass) {
                Set<String> classGenerics = Set.copyOf(klass.genericParameters());
                for (Ast.FieldDecl field : klass.fields()) {
                    validateTypeRef(moduleName, field.type(), classGenerics, "class field " + klass.name() + "." + field.name());
                    if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), classGenerics);
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    Set<String> generics = new LinkedHashSet<>(classGenerics);
                    generics.addAll(method.genericParameters());
                    validateTypeRef(moduleName, method.explicitReceiverType(), generics, "method receiver " + klass.name() + "." + method.name());
                    validateParams(moduleName, method.parameters(), generics, "method " + klass.name() + "." + method.name());
                    validateTypeRef(moduleName, method.returnType(), generics, "method return " + klass.name() + "." + method.name());
                    validateNoTraitRuntimeTypes(moduleName, method.body(), generics);
                }
            } else if (declaration instanceof Ast.TraitDecl trait) {
                Set<String> traitGenerics = Set.copyOf(trait.genericParameters());
                for (Ast.FieldDecl field : trait.fields()) {
                    validateTypeRef(moduleName, field.type(), traitGenerics, "trait field " + trait.name() + "." + field.name());
                    if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), traitGenerics);
                }
                for (Ast.MethodDecl method : trait.methods()) {
                    Set<String> generics = new LinkedHashSet<>(traitGenerics);
                    generics.addAll(method.genericParameters());
                    validateTypeRef(moduleName, method.explicitReceiverType(), generics, "trait receiver " + trait.name() + "." + method.name());
                    validateParams(moduleName, method.parameters(), generics, "trait method " + trait.name() + "." + method.name());
                    validateTypeRef(moduleName, method.returnType(), generics, "trait method return " + trait.name() + "." + method.name());
                    validateNoTraitRuntimeTypes(moduleName, method.body(), generics);
                }
            } else if (declaration instanceof Ast.InterfaceDecl iface) {
                Set<String> interfaceGenerics = Set.copyOf(iface.genericParameters());
                for (Ast.InterfaceMember member : iface.members()) {
                    if (member instanceof Ast.InterfaceFunctionDecl function) {
                        Set<String> generics = new LinkedHashSet<>(interfaceGenerics);
                        generics.addAll(function.genericParameters());
                        validateParams(moduleName, function.parameters(), generics, "interface function " + iface.name() + "." + function.name());
                        validateTypeRef(moduleName, function.returnType(), generics, "interface function return " + iface.name() + "." + function.name());
                    } else if (member instanceof Ast.InterfaceFieldDecl field) {
                        validateTypeRef(moduleName, field.type(), interfaceGenerics, "interface data requirement " + iface.name() + "." + field.name());
                    }
                }
            } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                validateTypeRef(moduleName, alias.target(), Set.copyOf(alias.genericParameters()), "type alias " + alias.name());
            }
        }

        private void validateNoTraitRuntimeTypes(
                String moduleName,
                List<Ast.Stmt> statements,
                Set<String> generics) {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.BindingStmt binding) {
                    validateTypeRef(moduleName, binding.declaredType(), generics, "binding " + binding.name());
                    validateNoTraitRuntimeTypes(moduleName, binding.initializer(), generics);
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateNoTraitRuntimeTypes(moduleName, destructure.initializer(), generics);
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateNoTraitRuntimeTypes(moduleName, returned.value(), generics);
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateNoTraitRuntimeTypes(moduleName, expression.expression(), generics);
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateNoTraitRuntimeTypes(moduleName, deferred.expression(), generics);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateNoTraitRuntimeTypes(moduleName, branch.condition(), generics);
                        validateNoTraitRuntimeTypes(moduleName, branch.body(), generics);
                    }
                    validateNoTraitRuntimeTypes(moduleName, conditional.elseBody(), generics);
                } else if (statement instanceof Ast.MatchStmt matched) {
                    validateNoTraitRuntimeTypes(moduleName, matched.value(), generics);
                    for (Ast.MatchArm arm : matched.arms()) validateNoTraitRuntimeTypes(moduleName, arm.body(), generics);
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateNoTraitRuntimeTypes(moduleName, attempted.body(), generics);
                    validateNoTraitRuntimeTypes(moduleName, attempted.catchBody(), generics);
                    validateNoTraitRuntimeTypes(moduleName, attempted.finallyBody(), generics);
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateNoTraitRuntimeTypes(moduleName, loop.iterable(), generics);
                    validateNoTraitRuntimeTypes(moduleName, loop.body(), generics);
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) validateNoTraitRuntimeTypes(moduleName, List.of(loop.initializer()), generics);
                    if (loop.condition() != null) validateNoTraitRuntimeTypes(moduleName, loop.condition(), generics);
                    if (loop.update() != null) validateNoTraitRuntimeTypes(moduleName, loop.update(), generics);
                    validateNoTraitRuntimeTypes(moduleName, loop.body(), generics);
                }
            }
        }

        private void validateNoTraitRuntimeTypes(
                String moduleName,
                Ast.Expr expression,
                Set<String> generics) {
            if (expression instanceof Ast.NewExpr created) {
                validateTypeRef(moduleName, created.type(), generics, "constructor type");
                for (Ast.Expr argument : created.arguments()) validateNoTraitRuntimeTypes(moduleName, argument, generics);
            } else if (expression instanceof Ast.MemberExpr member) {
                validateNoTraitRuntimeTypes(moduleName, member.receiver(), generics);
            } else if (expression instanceof Ast.CallExpr call) {
                validateNoTraitRuntimeTypes(moduleName, call.callee(), generics);
                for (Ast.Expr argument : call.arguments()) validateNoTraitRuntimeTypes(moduleName, argument, generics);
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateNoTraitRuntimeTypes(moduleName, binary.left(), generics);
                validateNoTraitRuntimeTypes(moduleName, binary.right(), generics);
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateNoTraitRuntimeTypes(moduleName, unary.operand(), generics);
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateNoTraitRuntimeTypes(moduleName, assignment.target(), generics);
                validateNoTraitRuntimeTypes(moduleName, assignment.value(), generics);
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateNoTraitRuntimeTypes(moduleName, conditional.condition(), generics);
                validateNoTraitRuntimeTypes(moduleName, conditional.whenTrue(), generics);
                validateNoTraitRuntimeTypes(moduleName, conditional.whenFalse(), generics);
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateNoTraitRuntimeTypes(moduleName, indexed.receiver(), generics);
                validateNoTraitRuntimeTypes(moduleName, indexed.index(), generics);
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateNoTraitRuntimeTypes(moduleName, awaited.expression(), generics);
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) validateNoTraitRuntimeTypes(moduleName, item, generics);
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) validateNoTraitRuntimeTypes(moduleName, item, generics);
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) validateNoTraitRuntimeTypes(moduleName, field.value(), generics);
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                for (Ast.Param parameter : lambda.parameters()) {
                    validateTypeRef(moduleName, parameter.type(), generics, "lambda parameter " + parameter.name());
                }
                if (lambda.expressionBody() != null) validateNoTraitRuntimeTypes(moduleName, lambda.expressionBody(), generics);
                if (lambda.blockBody() != null) validateNoTraitRuntimeTypes(moduleName, lambda.blockBody(), generics);
            }
        }

        private void validateParams(
                String moduleName,
                List<Ast.Param> parameters,
                Set<String> generics,
                String where) {
            for (Ast.Param parameter : parameters) {
                validateTypeRef(moduleName, parameter.type(), generics, where + " parameter " + parameter.name());
            }
        }

        private void validateTypeRef(
                String moduleName,
                Ast.TypeRef type,
                Set<String> generics,
                String where) {
            if (type == null) return;
            if (type.isBorrow()) {
                validateTypeRef(moduleName, type.borrowedTarget(), generics, where);
                return;
            }
            if (!generics.contains(type.name()) && isTraitName(moduleName, type.name())) {
                throw new IllegalArgumentException(
                        "trait '" + type.name() + "' cannot be used as a runtime type in " + where
                                + "; traits are composition units only—use an interface for contracts or a class for values");
            }
            for (Ast.TypeRef argument : type.arguments()) {
                validateTypeRef(moduleName, argument, generics, where);
            }
        }

        private void validateNoTraitInstantiation() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    validateNoTraitInstantiation(module.name(), declaration);
                }
            }
        }

        private void validateNoTraitInstantiation(String moduleName, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                validateNoTraitInstantiation(moduleName, function.body());
            } else if (declaration instanceof Ast.InitDecl init) {
                validateNoTraitInstantiation(moduleName, init.body());
            } else if (declaration instanceof Ast.FieldDecl field) {
                if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
            } else if (declaration instanceof Ast.ClassDecl klass) {
                for (Ast.FieldDecl field : klass.fields()) {
                    if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    validateNoTraitInstantiation(moduleName, method.body());
                }
            } else if (declaration instanceof Ast.TraitDecl trait) {
                for (Ast.FieldDecl field : trait.fields()) {
                    if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
                }
                for (Ast.MethodDecl method : trait.methods()) {
                    validateNoTraitInstantiation(moduleName, method.body());
                }
            }
        }

        private void validateNoTraitInstantiation(String moduleName, List<Ast.Stmt> statements) {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.BindingStmt binding) {
                    validateNoTraitInstantiation(moduleName, binding.initializer());
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateNoTraitInstantiation(moduleName, destructure.initializer());
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateNoTraitInstantiation(moduleName, returned.value());
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateNoTraitInstantiation(moduleName, expression.expression());
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateNoTraitInstantiation(moduleName, deferred.expression());
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateNoTraitInstantiation(moduleName, branch.condition());
                        validateNoTraitInstantiation(moduleName, branch.body());
                    }
                    validateNoTraitInstantiation(moduleName, conditional.elseBody());
                } else if (statement instanceof Ast.MatchStmt matched) {
                    validateNoTraitInstantiation(moduleName, matched.value());
                    for (Ast.MatchArm arm : matched.arms()) validateNoTraitInstantiation(moduleName, arm.body());
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateNoTraitInstantiation(moduleName, attempted.body());
                    validateNoTraitInstantiation(moduleName, attempted.catchBody());
                    validateNoTraitInstantiation(moduleName, attempted.finallyBody());
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateNoTraitInstantiation(moduleName, loop.iterable());
                    validateNoTraitInstantiation(moduleName, loop.body());
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) validateNoTraitInstantiation(moduleName, List.of(loop.initializer()));
                    if (loop.condition() != null) validateNoTraitInstantiation(moduleName, loop.condition());
                    if (loop.update() != null) validateNoTraitInstantiation(moduleName, loop.update());
                    validateNoTraitInstantiation(moduleName, loop.body());
                }
            }
        }

        private void validateNoTraitInstantiation(String moduleName, Ast.Expr expression) {
            if (expression instanceof Ast.NewExpr created) {
                if (isTraitName(moduleName, created.type().name())) {
                    throw new IllegalArgumentException(
                            "trait '" + created.type().name()
                                    + "' cannot be instantiated; compose it into a class with 'with'");
                }
                for (Ast.Expr argument : created.arguments()) validateNoTraitInstantiation(moduleName, argument);
            } else if (expression instanceof Ast.MemberExpr member) {
                validateNoTraitInstantiation(moduleName, member.receiver());
            } else if (expression instanceof Ast.CallExpr call) {
                validateNoTraitInstantiation(moduleName, call.callee());
                for (Ast.Expr argument : call.arguments()) validateNoTraitInstantiation(moduleName, argument);
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateNoTraitInstantiation(moduleName, binary.left());
                validateNoTraitInstantiation(moduleName, binary.right());
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateNoTraitInstantiation(moduleName, unary.operand());
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateNoTraitInstantiation(moduleName, assignment.target());
                validateNoTraitInstantiation(moduleName, assignment.value());
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateNoTraitInstantiation(moduleName, conditional.condition());
                validateNoTraitInstantiation(moduleName, conditional.whenTrue());
                validateNoTraitInstantiation(moduleName, conditional.whenFalse());
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateNoTraitInstantiation(moduleName, indexed.receiver());
                validateNoTraitInstantiation(moduleName, indexed.index());
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateNoTraitInstantiation(moduleName, awaited.expression());
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) validateNoTraitInstantiation(moduleName, item);
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) validateNoTraitInstantiation(moduleName, item);
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) validateNoTraitInstantiation(moduleName, field.value());
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                if (lambda.expressionBody() != null) validateNoTraitInstantiation(moduleName, lambda.expressionBody());
                if (lambda.blockBody() != null) validateNoTraitInstantiation(moduleName, lambda.blockBody());
            }
        }

        private boolean isTraitName(String moduleName, String name) {
            if (qualifiedTraits.containsKey(moduleName + "." + name)) return true;
            return name.contains(".") && qualifiedTraits.containsKey(name);
        }

        private void indexTraits() {
            for (Ast.ModuleDecl module : program.modules()) {
                Set<String> occupiedTypeNames = new HashSet<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass) occupiedTypeNames.add(klass.name());
                    else if (declaration instanceof Ast.InterfaceDecl iface) occupiedTypeNames.add(iface.name());
                    else if (declaration instanceof Ast.TypeAliasDecl alias) occupiedTypeNames.add(alias.name());
                }

                for (Ast.Decl declaration : module.declarations()) {
                    if (!(declaration instanceof Ast.TraitDecl trait)) continue;
                    if (trait.name().equals("Option")) {
                        throw new IllegalArgumentException("trait cannot redefine built-in type 'Option'");
                    }
                    if (occupiedTypeNames.contains(trait.name())) {
                        throw new IllegalArgumentException(
                                "trait '" + module.name() + "." + trait.name()
                                        + "' collides with an existing class/interface/type alias in the same type namespace");
                    }

                    TraitBinding binding = new TraitBinding(module.name(), trait);
                    String qualified = module.name() + "." + trait.name();
                    if (qualifiedTraits.putIfAbsent(qualified, binding) != null) {
                        throw new IllegalArgumentException("duplicate trait '" + qualified + "'");
                    }

                    TraitBinding previous = unqualifiedTraits.putIfAbsent(trait.name(), binding);
                    if (previous != null && previous != binding) {
                        ambiguousTraits.add(trait.name());
                        unqualifiedTraits.remove(trait.name());
                    }
                }
            }
        }

        private Ast.ClassDecl validationClass(String moduleName, Ast.TraitDecl trait) {
            List<Ast.TypeRef> arguments = trait.genericParameters().stream()
                    .map(Ast.TypeRef::simple)
                    .toList();
            Ast.TypeRef reference = new Ast.TypeRef(
                    trait.name(),
                    arguments,
                    false);
            Material material = materialize(
                    moduleName,
                    reference,
                    Set.copyOf(trait.genericParameters()),
                    new ArrayDeque<>());

            List<Ast.FieldDecl> fields = material.fields.values().stream()
                    .map(FieldEntry::field)
                    .toList();
            List<Ast.MethodDecl> methods = material.methods.values().stream()
                    .map(MethodEntry::method)
                    .toList();

            return new Ast.ClassDecl(
                    VALIDATION_CLASS_PREFIX + moduleName + "$" + trait.name(),
                    true,
                    trait.genericParameters(),
                    List.of(),
                    List.copyOf(material.interfaces.values()),
                    List.of(),
                    fields,
                    methods);
        }

        private Ast.ClassDecl composeClass(String moduleName, Ast.ClassDecl klass) {
            if (klass.traits().isEmpty()) return klass;

            LinkedHashMap<String, FieldEntry> traitFields = new LinkedHashMap<>();
            LinkedHashMap<String, List<MethodEntry>> traitMethods = new LinkedHashMap<>();
            LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
            for (Ast.TypeRef iface : klass.interfaces()) {
                mergeInterface(interfaces, iface, "class " + klass.name());
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            for (Ast.TypeRef traitRef : klass.traits()) {
                Material material = materialize(moduleName, traitRef, classGenerics, new ArrayDeque<>());

                for (FieldEntry field : material.fields.values()) {
                    FieldEntry previous = traitFields.putIfAbsent(field.field().name(), field);
                    if (previous != null && !previous.origin().equals(field.origin())) {
                        throw new IllegalArgumentException(
                                "trait state collision for field '" + field.field().name()
                                        + "' in class '" + klass.name() + "': "
                                        + previous.origin() + " vs " + field.origin());
                    }
                }

                for (Map.Entry<String, MethodEntry> method : material.methods.entrySet()) {
                    traitMethods.computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }

                for (Ast.TypeRef iface : material.interfaces.values()) {
                    mergeInterface(interfaces, iface, "trait composition of " + klass.name());
                }
            }

            for (Ast.FieldDecl field : klass.fields()) {
                if (traitFields.containsKey(field.name())) {
                    throw new IllegalArgumentException(
                            "class '" + klass.name() + "' field '" + field.name()
                                    + "' collides with composed trait state; trait storage cannot be shadowed");
                }
            }

            LinkedHashMap<String, Ast.MethodDecl> localMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic()) localMethods.put(methodKey(method), method);
            }

            List<Ast.MethodDecl> composedMethods = new ArrayList<>();
            for (Map.Entry<String, List<MethodEntry>> entry : traitMethods.entrySet()) {
                String key = entry.getKey();
                List<MethodEntry> candidates = dedupeOrigins(entry.getValue());
                Ast.MethodDecl local = localMethods.get(key);

                if (local != null) {
                    for (MethodEntry candidate : candidates) {
                        if (candidate.method().visibility() == Ast.Visibility.PRIVATE) {
                            throw new IllegalArgumentException(
                                    "class '" + klass.name() + "' method '" + key
                                            + "' collides with trait-private method from "
                                            + candidate.origin()
                                            + "; private trait helpers cannot be overridden");
                        }
                        requireCompatible(
                                local,
                                candidate.method(),
                                "class override " + klass.name() + "." + key);
                    }
                    continue;
                }

                Ast.MethodDecl selected = selectTraitMethod(klass, key, candidates);
                if (selected != null) composedMethods.add(selected);
            }

            List<Ast.FieldDecl> fields = new ArrayList<>(traitFields.size() + klass.fields().size());
            for (FieldEntry entry : traitFields.values()) fields.add(entry.field());
            fields.addAll(klass.fields());

            List<Ast.MethodDecl> methods = new ArrayList<>(composedMethods.size() + klass.methods().size());
            methods.addAll(composedMethods);
            methods.addAll(klass.methods());

            return new Ast.ClassDecl(
                    klass.name(),
                    klass.isAbstract(),
                    klass.genericParameters(),
                    klass.parents(),
                    List.copyOf(interfaces.values()),
                    List.of(),
                    fields,
                    methods);
        }

        private Ast.MethodDecl selectTraitMethod(
                Ast.ClassDecl klass,
                String key,
                List<MethodEntry> candidates) {
            if (candidates.isEmpty()) return null;

            Ast.MethodDecl baseline = candidates.getFirst().method();
            for (MethodEntry candidate : candidates) {
                requireCompatible(
                        baseline,
                        candidate.method(),
                        "trait method " + klass.name() + "." + key);
            }

            List<MethodEntry> concrete = candidates.stream()
                    .filter(entry -> !entry.method().isAbstract())
                    .toList();

            if (concrete.size() > 1) {
                throw new IllegalArgumentException(
                        "ambiguous trait method '" + key + "' in class '" + klass.name()
                                + "' from " + concrete.stream().map(MethodEntry::origin).toList()
                                + "; declare the method on the class to resolve the conflict");
            }
            if (concrete.size() == 1) return concrete.getFirst().method();

            if (!klass.isAbstract()) {
                throw new IllegalArgumentException(
                        "concrete class '" + klass.name()
                                + "' must implement trait requirement '" + key + "'");
            }
            return candidates.getFirst().method();
        }

        private Material materialize(
                String hostModule,
                Ast.TypeRef traitRef,
                Set<String> hostGenerics,
                ArrayDeque<String> stack) {
            TraitBinding binding = resolveTrait(hostModule, traitRef.name());

            // Method bodies retain their lexical module environment after
            // flattening. Restrict v0 composition to the declaring module so
            // that this transformation cannot silently change lexical lookup.
            if (!binding.module().equals(hostModule)) {
                throw new IllegalArgumentException(
                        "trait '" + traitRef.name() + "' is declared in module '"
                                + binding.module()
                                + "' but v0 trait composition is module-local");
            }

            Map<String, Ast.TypeRef> substitutions =
                    bindGenerics(binding.declaration(), traitRef, hostGenerics);
            String applicationKey = applicationKey(binding, substitutions);

            Material cached = materialCache.get(applicationKey);
            if (cached != null) return cached;
            if (stack.contains(applicationKey)) {
                throw new IllegalArgumentException(
                        "trait composition cycle: " + stack + " -> " + applicationKey);
            }
            stack.addLast(applicationKey);

            Material material = new Material();
            LinkedHashMap<String, List<MethodEntry>> inheritedMethods = new LinkedHashMap<>();

            for (Ast.TypeRef nestedRaw : binding.declaration().traits()) {
                Ast.TypeRef nested = substitute(nestedRaw, substitutions);
                Material nestedMaterial =
                        materialize(hostModule, nested, hostGenerics, stack);

                for (FieldEntry field : nestedMaterial.fields.values()) {
                    mergeField(material, field);
                }
                for (Map.Entry<String, MethodEntry> method : nestedMaterial.methods.entrySet()) {
                    inheritedMethods
                            .computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }
                for (Ast.TypeRef iface : nestedMaterial.interfaces.values()) {
                    mergeInterface(
                            material.interfaces,
                            iface,
                            "trait " + binding.declaration().name());
                }
            }

            for (Ast.TypeRef ifaceRaw : binding.declaration().interfaces()) {
                mergeInterface(
                        material.interfaces,
                        substitute(ifaceRaw, substitutions),
                        "trait " + binding.declaration().name());
            }

            for (Ast.FieldDecl raw : binding.declaration().fields()) {
                if (raw.initializer() == null) {
                    throw new IllegalArgumentException(
                            "trait state field '" + binding.declaration().name() + "."
                                    + raw.name()
                                    + "' requires an initializer because trait state is not "
                                    + "a host-class constructor parameter");
                }

                Ast.FieldDecl field = new Ast.FieldDecl(
                        raw.name(),
                        raw.visibility(),
                        raw.bindingKind(),
                        substitute(raw.type(), substitutions),
                        substitute(raw.initializer(), substitutions),
                        binding.declaration().name());

                mergeField(
                        material,
                        new FieldEntry(
                                field,
                                applicationKey + "::" + raw.name()));
            }

            Set<String> lexicalFields = new LinkedHashSet<>();
            for (Ast.FieldDecl field : binding.declaration().fields()) lexicalFields.add(field.name());
            Set<String> lexicalMethods = new LinkedHashSet<>();
            for (Ast.MethodDecl method : binding.declaration().methods()) lexicalMethods.add(method.name());
            for (String inheritedKey : inheritedMethods.keySet()) {
                lexicalMethods.add(inheritedKey.substring(0, inheritedKey.lastIndexOf('/')));
            }

            LinkedHashMap<String, Ast.MethodDecl> ownMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl raw : binding.declaration().methods()) {
                if (raw.isAbstract() && raw.visibility() == Ast.Visibility.PRIVATE) {
                    throw new IllegalArgumentException(
                            "trait '" + binding.declaration().name() + "' has private abstract method '"
                                    + raw.name()
                                    + "'; abstract trait requirements must be public so a host class can satisfy them");
                }
                validateTraitSelfAccess(binding.declaration().name(), raw.body(), lexicalFields, lexicalMethods);
                Ast.MethodDecl method = withCompositionOwner(
                        substitute(raw, substitutions),
                        binding.declaration().name());
                String key = methodKey(method);
                Ast.MethodDecl previous = ownMethods.putIfAbsent(key, method);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate trait method '"
                                    + binding.declaration().name() + "." + key + "'");
                }
            }

            Set<String> methodKeys = new LinkedHashSet<>();
            methodKeys.addAll(inheritedMethods.keySet());
            methodKeys.addAll(ownMethods.keySet());

            for (String key : methodKeys) {
                Ast.MethodDecl override = ownMethods.get(key);
                List<MethodEntry> inherited =
                        dedupeOrigins(inheritedMethods.getOrDefault(key, List.of()));

                if (override != null) {
                    for (MethodEntry candidate : inherited) {
                        if (candidate.method().visibility() == Ast.Visibility.PRIVATE) {
                            throw new IllegalArgumentException(
                                    "trait '" + binding.declaration().name()
                                            + "' method '" + key
                                            + "' collides with private method inherited from "
                                            + candidate.origin()
                                            + "; private nested-trait helpers are lexical and cannot be overridden");
                        }
                        requireCompatible(
                                override,
                                candidate.method(),
                                "trait override "
                                        + binding.declaration().name() + "." + key);
                    }
                    material.methods.put(
                            key,
                            new MethodEntry(
                                    override,
                                    applicationKey + "::" + key));
                    continue;
                }

                if (inherited.isEmpty()) continue;

                Ast.MethodDecl baseline = inherited.getFirst().method();
                for (MethodEntry candidate : inherited) {
                    requireCompatible(
                            baseline,
                            candidate.method(),
                            "nested trait method "
                                    + binding.declaration().name() + "." + key);
                }

                List<MethodEntry> concrete = inherited.stream()
                        .filter(entry -> !entry.method().isAbstract())
                        .toList();
                if (concrete.size() > 1) {
                    throw new IllegalArgumentException(
                            "trait '" + binding.declaration().name()
                                    + "' inherits ambiguous concrete method '" + key
                                    + "'; override it in the trait to resolve the conflict");
                }

                MethodEntry selected =
                        concrete.isEmpty() ? inherited.getFirst() : concrete.getFirst();
                material.methods.put(key, selected);
            }

            stack.removeLast();
            materialCache.put(applicationKey, material);
            return material;
        }

        private void mergeField(Material material, FieldEntry incoming) {
            FieldEntry previous =
                    material.fields.putIfAbsent(incoming.field().name(), incoming);
            if (previous != null && !previous.origin().equals(incoming.origin())) {
                throw new IllegalArgumentException(
                        "trait state collision for field '" + incoming.field().name()
                                + "': " + previous.origin() + " vs " + incoming.origin());
            }
        }

        private void mergeInterface(
                Map<String, Ast.TypeRef> interfaces,
                Ast.TypeRef incoming,
                String owner) {
            Ast.TypeRef previous = interfaces.putIfAbsent(incoming.name(), incoming);
            if (previous != null && !previous.equals(incoming)) {
                throw new IllegalArgumentException(
                        "conflicting interface instantiations for '" + incoming.name()
                                + "' in " + owner + ": " + previous + " vs " + incoming);
            }
        }

        private TraitBinding resolveTrait(String hostModule, String name) {
            TraitBinding local = qualifiedTraits.get(hostModule + "." + name);
            if (local != null) return local;

            if (name.contains(".")) {
                TraitBinding exact = qualifiedTraits.get(name);
                if (exact != null) return exact;
            }

            if (ambiguousTraits.contains(name)) {
                throw new IllegalArgumentException(
                        "ambiguous trait '" + name + "'; qualify it with its module");
            }

            TraitBinding found = unqualifiedTraits.get(name);
            if (found == null) {
                throw new IllegalArgumentException("unknown trait '" + name + "'");
            }
            return found;
        }

        private Map<String, Ast.TypeRef> bindGenerics(
                Ast.TraitDecl trait,
                Ast.TypeRef reference,
                Set<String> hostGenerics) {
            List<String> parameters = trait.genericParameters();
            List<Ast.TypeRef> arguments = reference.arguments();

            if (parameters.isEmpty()) {
                if (!arguments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "trait '" + trait.name() + "' is not generic");
                }
                return Map.of();
            }

            List<Ast.TypeRef> bound = arguments;
            if (reference.inferArguments()) {
                ArrayList<Ast.TypeRef> inferred = new ArrayList<>();
                for (String parameter : parameters) {
                    if (!hostGenerics.contains(parameter)) {
                        throw new IllegalArgumentException(
                                "cannot infer trait generic '" + parameter
                                        + "' for " + trait.name()
                                        + "; use explicit type arguments");
                    }
                    inferred.add(Ast.TypeRef.simple(parameter));
                }
                bound = inferred;
            }

            if (bound.size() != parameters.size()) {
                throw new IllegalArgumentException(
                        "trait '" + trait.name() + "' expects "
                                + parameters.size()
                                + " type arguments but got " + bound.size());
            }

            LinkedHashMap<String, Ast.TypeRef> result = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                result.put(parameters.get(i), bound.get(i));
            }
            return result;
        }

        private String applicationKey(
                TraitBinding binding,
                Map<String, Ast.TypeRef> substitutions) {
            StringBuilder key =
                    new StringBuilder(binding.module())
                            .append('.')
                            .append(binding.declaration().name())
                            .append('<');
            for (String parameter : binding.declaration().genericParameters()) {
                key.append(parameter)
                        .append('=')
                        .append(substitutions.get(parameter))
                        .append(';');
            }
            return key.append('>').toString();
        }

        private Ast.TypeRef substitute(
                Ast.TypeRef type,
                Map<String, Ast.TypeRef> substitutions) {
            if (type == null) return null;

            Ast.TypeRef direct = substitutions.get(type.name());
            if (direct != null && type.arguments().isEmpty()) return direct;

            return new Ast.TypeRef(
                    type.name(),
                    type.arguments().stream()
                            .map(argument -> substitute(argument, substitutions))
                            .toList(),
                    type.inferArguments());
        }

        private List<Ast.Stmt> substituteStatements(
                List<Ast.Stmt> statements,
                Map<String, Ast.TypeRef> substitutions) {
            return statements.stream()
                    .map(statement -> substitute(statement, substitutions))
                    .toList();
        }

        private Ast.Stmt substitute(
                Ast.Stmt statement,
                Map<String, Ast.TypeRef> substitutions) {
            if (statement == null) return null;
            if (statement instanceof Ast.BindingStmt binding) {
                return new Ast.BindingStmt(
                        binding.kind(),
                        substitute(binding.declaredType(), substitutions),
                        binding.name(),
                        substitute(binding.initializer(), substitutions));
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                return new Ast.DestructureStmt(
                        destructure.bindings(),
                        substitute(destructure.initializer(), substitutions));
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return new Ast.ReturnStmt(
                        returned.value() == null ? null : substitute(returned.value(), substitutions));
            }
            if (statement instanceof Ast.ExprStmt expression) {
                return new Ast.ExprStmt(substitute(expression.expression(), substitutions));
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                return new Ast.DeferStmt(substitute(deferred.expression(), substitutions));
            }
            if (statement instanceof Ast.IfStmt conditional) {
                List<Ast.IfBranch> branches = conditional.branches().stream()
                        .map(branch -> new Ast.IfBranch(
                                substitute(branch.condition(), substitutions),
                                substituteStatements(branch.body(), substitutions)))
                        .toList();
                return new Ast.IfStmt(
                        branches,
                        substituteStatements(conditional.elseBody(), substitutions));
            }
            if (statement instanceof Ast.MatchStmt matched) {
                return new Ast.MatchStmt(
                        substitute(matched.value(), substitutions),
                        matched.arms().stream()
                                .map(arm -> new Ast.MatchArm(
                                        arm.pattern(),
                                        substituteStatements(arm.body(), substitutions)))
                                .toList());
            }
            if (statement instanceof Ast.TryStmt attempted) {
                return new Ast.TryStmt(
                        substituteStatements(attempted.body(), substitutions),
                        attempted.errorName(),
                        substituteStatements(attempted.catchBody(), substitutions),
                        substituteStatements(attempted.finallyBody(), substitutions));
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                return new Ast.ForOfStmt(
                        loop.bindingKind(),
                        loop.bindingName(),
                        substitute(loop.iterable(), substitutions),
                        substituteStatements(loop.body(), substitutions));
            }
            Ast.ForStmt loop = (Ast.ForStmt) statement;
            return new Ast.ForStmt(
                    substitute(loop.initializer(), substitutions),
                    loop.condition() == null ? null : substitute(loop.condition(), substitutions),
                    loop.update() == null ? null : substitute(loop.update(), substitutions),
                    substituteStatements(loop.body(), substitutions));
        }

        private Ast.Expr substitute(
                Ast.Expr expression,
                Map<String, Ast.TypeRef> substitutions) {
            if (expression == null) return null;
            if (expression instanceof Ast.LiteralExpr || expression instanceof Ast.NameExpr) {
                return expression;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                return new Ast.BinaryExpr(
                        binary.operator(),
                        substitute(binary.left(), substitutions),
                        substitute(binary.right(), substitutions));
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return new Ast.UnaryExpr(
                        unary.operator(),
                        substitute(unary.operand(), substitutions));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                return new Ast.AssignExpr(
                        substitute(assignment.target(), substitutions),
                        substitute(assignment.value(), substitutions));
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                return new Ast.ConditionalExpr(
                        substitute(conditional.condition(), substitutions),
                        substitute(conditional.whenTrue(), substitutions),
                        substitute(conditional.whenFalse(), substitutions));
            }
            if (expression instanceof Ast.CallExpr call) {
                return new Ast.CallExpr(
                        substitute(call.callee(), substitutions),
                        call.arguments().stream()
                                .map(argument -> substitute(argument, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.MemberExpr member) {
                return new Ast.MemberExpr(
                        substitute(member.receiver(), substitutions),
                        member.member());
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                return new Ast.IndexExpr(
                        substitute(indexed.receiver(), substitutions),
                        substitute(indexed.index(), substitutions));
            }
            if (expression instanceof Ast.NewExpr created) {
                return new Ast.NewExpr(
                        substitute(created.type(), substitutions),
                        created.arguments().stream()
                                .map(argument -> substitute(argument, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                return new Ast.AwaitExpr(
                        substitute(awaited.expression(), substitutions));
            }
            if (expression instanceof Ast.ListExpr list) {
                return new Ast.ListExpr(
                        list.elements().stream()
                                .map(item -> substitute(item, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                return new Ast.TupleExpr(
                        tuple.elements().stream()
                                .map(item -> substitute(item, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.ObjectExpr object) {
                return new Ast.ObjectExpr(
                        object.fields().stream()
                                .map(field -> new Ast.ObjectField(
                                        field.name(),
                                        substitute(field.value(), substitutions)))
                                .toList());
            }
            Ast.LambdaExpr lambda = (Ast.LambdaExpr) expression;
            List<Ast.Param> parameters = lambda.parameters().stream()
                    .map(parameter -> new Ast.Param(
                            substitute(parameter.type(), substitutions),
                            parameter.name(),
                            parameter.structural(),
                            parameter.mode()))
                    .toList();
            return new Ast.LambdaExpr(
                    parameters,
                    lambda.expressionBody() == null
                            ? null
                            : substitute(lambda.expressionBody(), substitutions),
                    lambda.blockBody() == null
                            ? null
                            : substituteStatements(lambda.blockBody(), substitutions));
        }

        private Ast.MethodDecl substitute(
                Ast.MethodDecl method,
                Map<String, Ast.TypeRef> substitutions) {
            Map<String, Ast.TypeRef> effective =
                    new LinkedHashMap<>(substitutions);
            for (String methodGeneric : method.genericParameters()) {
                effective.remove(methodGeneric);
            }

            List<Ast.Param> parameters = method.parameters().stream()
                    .map(parameter -> new Ast.Param(
                            substitute(parameter.type(), effective),
                            parameter.name(),
                            parameter.structural(),
                            parameter.mode()))
                    .toList();

            List<Ast.Annotation> annotations = method.annotations().stream()
                    .map(annotation -> new Ast.Annotation(
                            annotation.name(),
                            annotation.arguments().stream()
                                    .map(argument -> substitute(argument, effective))
                                    .toList()))
                    .toList();

            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    substitute(method.explicitReceiverType(), effective),
                    method.genericParameters(),
                    parameters,
                    substitute(method.returnType(), effective),
                    annotations,
                    substituteStatements(method.body(), effective),
                    method.compositionOwner());
        }

        private Ast.MethodDecl withCompositionOwner(Ast.MethodDecl method, String owner) {
            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    method.explicitReceiverType(),
                    method.genericParameters(),
                    method.parameters(),
                    method.returnType(),
                    method.annotations(),
                    method.body(),
                    owner);
        }

        private void validateTraitSelfAccess(
                String traitName,
                List<Ast.Stmt> statements,
                Set<String> fields,
                Set<String> methods) {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.BindingStmt binding) {
                    validateTraitSelfAccess(traitName, binding.initializer(), fields, methods);
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateTraitSelfAccess(traitName, destructure.initializer(), fields, methods);
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateTraitSelfAccess(traitName, returned.value(), fields, methods);
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateTraitSelfAccess(traitName, expression.expression(), fields, methods);
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateTraitSelfAccess(traitName, deferred.expression(), fields, methods);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateTraitSelfAccess(traitName, branch.condition(), fields, methods);
                        validateTraitSelfAccess(traitName, branch.body(), fields, methods);
                    }
                    validateTraitSelfAccess(traitName, conditional.elseBody(), fields, methods);
                } else if (statement instanceof Ast.MatchStmt matched) {
                    validateTraitSelfAccess(traitName, matched.value(), fields, methods);
                    for (Ast.MatchArm arm : matched.arms()) validateTraitSelfAccess(traitName, arm.body(), fields, methods);
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateTraitSelfAccess(traitName, attempted.body(), fields, methods);
                    validateTraitSelfAccess(traitName, attempted.catchBody(), fields, methods);
                    validateTraitSelfAccess(traitName, attempted.finallyBody(), fields, methods);
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateTraitSelfAccess(traitName, loop.iterable(), fields, methods);
                    validateTraitSelfAccess(traitName, loop.body(), fields, methods);
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) {
                        validateTraitSelfAccess(traitName, List.of(loop.initializer()), fields, methods);
                    }
                    if (loop.condition() != null) {
                        validateTraitSelfAccess(traitName, loop.condition(), fields, methods);
                    }
                    if (loop.update() != null) {
                        validateTraitSelfAccess(traitName, loop.update(), fields, methods);
                    }
                    validateTraitSelfAccess(traitName, loop.body(), fields, methods);
                }
            }
        }

        private void validateTraitSelfAccess(
                String traitName,
                Ast.Expr expression,
                Set<String> fields,
                Set<String> methods) {
            if (expression instanceof Ast.MemberExpr member) {
                if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("self")) {
                    if (!fields.contains(member.member()) && !methods.contains(member.member())) {
                        throw new IllegalArgumentException(
                                "trait '" + traitName + "' accesses undeclared self member '"
                                        + member.member()
                                        + "'; declare trait state or an abstract method requirement first");
                    }
                }
                validateTraitSelfAccess(traitName, member.receiver(), fields, methods);
            } else if (expression instanceof Ast.CallExpr call) {
                validateTraitSelfAccess(traitName, call.callee(), fields, methods);
                for (Ast.Expr argument : call.arguments()) {
                    validateTraitSelfAccess(traitName, argument, fields, methods);
                }
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateTraitSelfAccess(traitName, binary.left(), fields, methods);
                validateTraitSelfAccess(traitName, binary.right(), fields, methods);
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateTraitSelfAccess(traitName, unary.operand(), fields, methods);
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateTraitSelfAccess(traitName, assignment.target(), fields, methods);
                validateTraitSelfAccess(traitName, assignment.value(), fields, methods);
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateTraitSelfAccess(traitName, conditional.condition(), fields, methods);
                validateTraitSelfAccess(traitName, conditional.whenTrue(), fields, methods);
                validateTraitSelfAccess(traitName, conditional.whenFalse(), fields, methods);
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateTraitSelfAccess(traitName, indexed.receiver(), fields, methods);
                validateTraitSelfAccess(traitName, indexed.index(), fields, methods);
            } else if (expression instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) {
                    validateTraitSelfAccess(traitName, argument, fields, methods);
                }
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateTraitSelfAccess(traitName, awaited.expression(), fields, methods);
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) {
                    validateTraitSelfAccess(traitName, item, fields, methods);
                }
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) {
                    validateTraitSelfAccess(traitName, item, fields, methods);
                }
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) {
                    validateTraitSelfAccess(traitName, field.value(), fields, methods);
                }
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                if (lambda.expressionBody() != null) {
                    validateTraitSelfAccess(traitName, lambda.expressionBody(), fields, methods);
                }
                if (lambda.blockBody() != null) {
                    validateTraitSelfAccess(traitName, lambda.blockBody(), fields, methods);
                }
            }
        }

        private List<MethodEntry> dedupeOrigins(List<MethodEntry> entries) {
            LinkedHashMap<String, MethodEntry> result = new LinkedHashMap<>();
            for (MethodEntry entry : entries) {
                result.putIfAbsent(entry.origin(), entry);
            }
            return List.copyOf(result.values());
        }

        private String methodKey(Ast.MethodDecl method) {
            return method.name() + "/" + method.arity();
        }

        private void requireCompatible(
                Ast.MethodDecl left,
                Ast.MethodDecl right,
                String where) {
            if (!sameContract(left, right)) {
                throw new IllegalArgumentException(
                        "incompatible trait method contracts in " + where
                                + ": " + describe(left) + " vs " + describe(right));
            }
        }

        private boolean sameContract(
                Ast.MethodDecl left,
                Ast.MethodDecl right) {
            if (left.async() != right.async()) return false;
            if (!left.genericParameters().equals(right.genericParameters())) return false;
            if (!java.util.Objects.equals(
                    left.explicitReceiverType(),
                    right.explicitReceiverType())) return false;
            if (!left.returnType().equals(right.returnType())) return false;
            if (left.parameters().size() != right.parameters().size()) return false;

            for (int i = 0; i < left.parameters().size(); i++) {
                Ast.Param a = left.parameters().get(i);
                Ast.Param b = right.parameters().get(i);
                if (!a.type().equals(b.type())
                        || a.structural() != b.structural()
                        || a.mode() != b.mode()) return false;
            }
            return true;
        }

        private String describe(Ast.MethodDecl method) {
            return method.name()
                    + method.parameters().stream()
                            .map(parameter -> parameter.type().toString())
                            .toList()
                    + " => " + method.returnType();
        }
    }
}
