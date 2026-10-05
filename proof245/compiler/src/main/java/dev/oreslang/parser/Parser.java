package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.parser.Token.Type.*;

public final class Parser {
    public static final String ROOT_MODULE = "__root__";

    private final List<Token> tokens;
    private int current;

    public Parser(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static Ast.Program parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    public Ast.Program parseProgram() {
        String namespace = null;
        if (match(NAMESPACE)) {
            namespace = consume(IDENT, "expected flat namespace name").lexeme();
            if (check(DOT)) throw error(peek(), "namespaces cannot be nested or dotted");
            consume(SEMICOLON, "namespace declaration must end with ';'");
        }

        List<Ast.ImportDecl> imports = new ArrayList<>();
        while (match(IMPORT)) imports.add(parseImport());

        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.Decl> rootDeclarations = new ArrayList<>();

        while (!check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers modifiers = parseModifiers();

            if (match(INIT)) {
                if (!annotations.isEmpty()
                        || modifiers.visibility != Ast.Visibility.PRIVATE
                        || modifiers.async || modifiers.isStatic || modifiers.isAbstract) {
                    throw error(previous(), "init routine does not accept annotations or modifiers");
                }
                rootDeclarations.add(parseInitRoutine());
                continue;
            }

            if (match(DEFINE)) {
                boolean afterDefineAbstract = match(ABSTRACT);
                boolean singleton = match(SINGLETON);
                if (match(MODULE)) {
                    if (afterDefineAbstract) throw error(previous(), "modules cannot be abstract");
                    if (modifiers.visibility != Ast.Visibility.PRIVATE || modifiers.async || modifiers.isStatic || modifiers.isAbstract) {
                        throw error(previous(), "modules do not accept function/class modifiers");
                    }
                    modules.add(parseModule(annotations, singleton));
                    continue;
                }
                if (singleton) throw error(previous(), "'singleton' may only qualify a module");
                if (match(CLASS)) {
                    rootDeclarations.add(parseClass(modifiers.isAbstract || afterDefineAbstract));
                    continue;
                }
                if (match(TRAIT)) {
                    rootDeclarations.add(parseTrait());
                    continue;
                }
                if (match(INTERFACE)) {
                    rootDeclarations.add(parseInterface(modifiers.visibility));
                    continue;
                }
                throw error(previous(), "expected module, class, trait, or interface after 'define'");
            }

            Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
            if (declaration == null) throw error(peek(), "expected module or top-level declaration");
            rootDeclarations.add(declaration);
        }

        if (!rootDeclarations.isEmpty()) {
            modules.add(new Ast.ModuleDecl(ROOT_MODULE, List.of(), rootDeclarations));
        }
        if (modules.isEmpty()) throw error(peek(), "a source file must define at least one module or top-level declaration");
        return new Ast.Program(namespace, imports, modules);
    }

    private Ast.ImportDecl parseImport() {
        Ast.ImportKind kind;
        List<String> names = new ArrayList<>();
        boolean wildcard = false;
        String namespace = null;

        if (match(STAR)) {
            kind = Ast.ImportKind.ALL;
            wildcard = true;
            consume(AS, "'import *' requires 'as <namespace>'");
            namespace = consume(IDENT, "expected import namespace").lexeme();
        } else {
            if (match(MODULE)) kind = Ast.ImportKind.MODULE;
            else if (match(CLASS)) kind = Ast.ImportKind.CLASS;
            else if (match(FNC)) kind = Ast.ImportKind.FUNCTION;
            else throw error(peek(), "expected module, class, fnc, or * after import");

            if (match(STAR)) {
                wildcard = true;
                consume(AS, "wildcard import requires 'as <namespace>'");
                namespace = consume(IDENT, "expected import namespace").lexeme();
            } else if (match(LBRACE)) {
                if (check(RBRACE)) throw error(peek(), "import selection cannot be empty");
                do names.add(consume(IDENT, "expected imported name").lexeme()); while (match(COMMA));
                consume(RBRACE, "expected '}' after imported names");
            } else {
                names.add(consume(IDENT, "expected imported name").lexeme());
            }
        }

        consume(FROM, "expected 'from' in import");
        String path = consume(STRING, "expected quoted import path").lexeme();
        if (path.isBlank()) throw error(previous(), "import path cannot be empty");
        consume(SEMICOLON, "expected ';' after import");
        return new Ast.ImportDecl(kind, names, wildcard, namespace, path);
    }

    private Ast.ModuleDecl parseModule(List<Ast.Annotation> annotations, boolean singleton) {
        String name = consume(IDENT, "expected flat module name").lexeme();
        if (check(DOT)) throw error(peek(), "modules cannot be nested or dotted");
        consume(AS, "module declarations require 'as' before the body");
        List<Ast.Decl> declarations = new ArrayList<>();
        while (!check(END) && !check(EOF)) declarations.add(parseModuleMember());
        consume(END, "expected 'end' to close module " + name);
        return new Ast.ModuleDecl(name, singleton, annotations, declarations);
    }

    private Ast.Decl parseModuleMember() {
        List<Ast.Annotation> annotations = parseAnnotations();
        Modifiers modifiers = parseModifiers();

        if (match(INIT)) {
            if (!annotations.isEmpty()
                    || modifiers.visibility != Ast.Visibility.PRIVATE
                    || modifiers.async || modifiers.isStatic || modifiers.isAbstract) {
                throw error(previous(), "init routine does not accept annotations or modifiers");
            }
            return parseInitRoutine();
        }

        if (match(DEFINE)) {
            boolean afterDefineAbstract = match(ABSTRACT);
            if (match(CLASS)) return parseClass(modifiers.isAbstract || afterDefineAbstract);
            if (match(TRAIT)) {
                if (afterDefineAbstract) throw error(previous(), "traits express requirements with abstract members, not 'abstract trait'");
                return parseTrait();
            }
            if (match(INTERFACE)) return parseInterface(modifiers.visibility);
            throw error(previous(), "expected class, trait, or interface after 'define'");
        }

        Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
        if (declaration != null) return declaration;
        throw error(peek(), "expected function, routine, class, interface, type, or binding declaration");
    }

    private Ast.Decl parseDeclarationAfterModifiers(List<Ast.Annotation> annotations, Modifiers modifiers) {
        if (match(FNC)) return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        if (match(ROUTINE)) return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE);
        if (match(TRAIT)) return parseTrait();
        if (match(INTERFACE)) return parseInterface(modifiers.visibility);
        if (match(TYPE)) return parseTypeAlias();
        if (isBindingKind(peek().type())) return parseModuleBinding(modifiers.visibility);
        return null;
    }

    private Ast.InitDecl parseInitRoutine() {
        consume(ROUTINE, "expected 'routine' after 'init'");
        consume(LPAREN, "init routine requires '()'");
        consume(RPAREN, "init routine cannot accept parameters");
        consume(FAT_ARROW, "init routine requires explicit '=> void'");
        consume(VOID, "init routine must return void");
        return new Ast.InitDecl(parseBlock());
    }

    private Ast.FunctionDecl parseFunction(List<Ast.Annotation> annotations, Modifiers modifiers, Ast.CallableKind kind) {
        if (modifiers.isStatic) throw error(previous(), "'static fnc' is only valid inside a class");
        if (modifiers.isAbstract) throw error(previous(), "top-level/module callables cannot be abstract");
        String name = consume(IDENT, "expected callable name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '('");
        java.util.Set<String> structuralNames = structuralAnnotationNames(annotations);
        List<Ast.Param> params = applyStructuralAnnotations(parseParametersUntil(RPAREN, structuralNames), annotations);
        consume(RPAREN, "expected ')' after parameters");
        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body = parseBlock();
        return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, generics, params,
                returnType, annotations, body);
    }

    private Ast.ClassDecl parseClass(boolean isAbstract) {
        String name = consume(IDENT, "expected class name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        // 'is' is the canonical contract-conformance spelling. implements/impl
        // remain accepted while existing source migrates.
        List<Ast.TypeRef> interfaces = match(IS, IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> traits = match(WITH) ? parseTypeRefList() : List.of();
        consume(AS, "class declarations require 'as' before the body");
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();

        while (!check(END) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (check(INIT)) {
                throw error(peek(), "classes cannot declare init routine; use instance field/constructor initialization");
            }
            if (isBindingKind(peek().type())) {
                if (mods.isStatic) throw error(peek(), "static data members are not implemented yet; static class functions use 'static fnc'");
                fields.add(parseField(mods.visibility));
                continue;
            }
            if (mods.isStatic) {
                consume(FNC, "static class functions must be declared with 'static fnc'");
                if (mods.isAbstract) throw error(previous(), "static class functions cannot be abstract");
            } else if (check(FNC)) {
                throw error(peek(), "instance methods omit 'fnc'; use 'static fnc' only for class functions");
            }
            methods.add(parseMethod(annotations, mods));
        }
        consume(END, "expected 'end' to close class " + name);
        return new Ast.ClassDecl(name, isAbstract, generics, parents, interfaces, traits, fields, methods);
    }

    private Ast.TraitDecl parseTrait() {
        String name = consume(IDENT, "expected trait name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> interfaces = match(IS, IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> traits = match(WITH) ? parseTypeRefList() : List.of();
        consume(AS, "trait declarations require 'as' before the body");

        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        while (!check(END) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (check(INIT)) throw error(peek(), "traits cannot declare init routines; trait state initializes as part of the host object");
            if (isBindingKind(peek().type())) {
                if (mods.isStatic) throw error(peek(), "traits cannot declare static data");
                fields.add(parseField(mods.visibility));
                continue;
            }
            if (mods.isStatic) throw error(peek(), "traits contain instance behavior only; static functions belong on classes or modules");
            if (check(FNC)) throw error(peek(), "trait instance methods omit 'fnc', like class instance methods");
            methods.add(parseMethod(annotations, mods));
        }
        consume(END, "expected 'end' to close trait " + name);
        return new Ast.TraitDecl(name, generics, interfaces, traits, fields, methods);
    }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility) {
        String name = consume(IDENT, "expected interface name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        boolean braceStyle = match(LBRACE);
        if (!braceStyle) match(AS); // canonical spelling; legacy interface ... end remains accepted for migration
        Token.Type terminator = braceStyle ? RBRACE : END;

        List<Ast.InterfaceMember> members = new ArrayList<>();
        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (!annotations.isEmpty() || mods.async || mods.isStatic || mods.isAbstract) {
                throw error(peek(), "interface members are signatures only and do not accept annotations, async, static, or abstract modifiers");
            }

            if (match(FNC)) {
                String memberName = consume(IDENT, "expected interface function name").lexeme();
                List<String> memberGenerics = parseGenericParameters();
                consume(LPAREN, "expected '(' after interface function name");
                List<Ast.Param> params = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after interface parameters");
                Ast.TypeRef returns = match(FAT_ARROW) ? parseTypeRef() : Ast.TypeRef.simple("void");
                consumeMemberTerminator(terminator, "interface function signature should end with ';'");
                members.add(new Ast.InterfaceFunctionDecl(memberName, memberGenerics, params, returns));
                continue;
            }

            if (check(IDENT) && checkNext(COLON)) {
                String fieldName = advance().lexeme();
                consume(COLON, "expected ':' after interface data requirement name");
                Ast.TypeRef type = parseTypeRef();
                consumeMemberTerminator(terminator, "interface data requirement should end with ';'");
                members.add(new Ast.InterfaceFieldDecl(fieldName, type));
                continue;
            }

            if (isBindingKind(peek().type())) advance();
            Ast.TypeRef type = parseTypeRef();
            String fieldName = consume(IDENT, "expected interface data requirement name").lexeme();
            consumeMemberTerminator(terminator, "interface data requirement should end with ';' and cannot have an initializer");
            members.add(new Ast.InterfaceFieldDecl(fieldName, type));
        }

        consume(terminator, braceStyle ? "expected '}' to close interface " + name : "expected 'end' to close interface " + name);
        return new Ast.InterfaceDecl(name, visibility, generics, parents, members);
    }

    private List<Ast.TypeRef> parseTypeRefList() {
        List<Ast.TypeRef> refs = new ArrayList<>();
        do refs.add(parseTypeRef()); while (match(COMMA));
        return refs;
    }

    private Ast.FieldDecl parseField(Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = parseTypeRef();
        String name = consume(IDENT, "expected field name").lexeme();
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        consumeStatementTerminator("field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, initializer);
    }

    private Ast.FieldDecl parseModuleBinding(Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "module bindings require an initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("module binding should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, initializer);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods) {
        String name = parseMethodName();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '(' after method name");

        Ast.TypeRef receiverType = null;
        List<Ast.Param> params;
        boolean mutableReceiver = check(MUT) && checkNext(SELF);
        boolean takeReceiver = check(IDENT) && peek().lexeme().equals("take") && checkNext(SELF);
        if (mods.isStatic && (check(SELF) || mutableReceiver || takeReceiver)) {
            throw error(peek(), "static class functions do not have a self receiver");
        }
        if (takeReceiver) {
            advance(); // contextual take
            consume(SELF, "consuming receiver syntax is 'take self'");
            Ast.TypeRef target = check(RPAREN) ? Ast.TypeRef.simple("self") : parseTypeRef();
            receiverType = target; // non-borrow receiver means ownership transfer
            consume(RPAREN, "expected ')' after consuming self receiver");
            consume(LPAREN, "explicit receiver form is method(take self)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else if (mutableReceiver) {
            advance(); // mut
            consume(SELF, "mutable receiver syntax is 'mut self'");
            Ast.TypeRef target = check(RPAREN) ? Ast.TypeRef.simple("self") : parseTypeRef();
            receiverType = Ast.TypeRef.borrowed(target, true);
            consume(RPAREN, "expected ')' after mutable self receiver");
            consume(LPAREN, "explicit receiver form is method(mut self)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else if (match(SELF)) {
            Ast.TypeRef target = check(RPAREN) ? Ast.TypeRef.simple("self") : parseTypeRef();
            receiverType = Ast.TypeRef.borrowed(target, false);
            consume(RPAREN, "expected ')' after explicit self receiver");
            consume(LPAREN, "explicit receiver form is method(self)(params) or method(self Type)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else {
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        }

        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body;
        if (mods.isAbstract) {
            consumeStatementTerminator("abstract method should end with ';'");
            body = List.of();
        } else body = parseBlock();
        return new Ast.MethodDecl(name, mods.visibility, mods.isStatic, mods.isAbstract, mods.async,
                receiverType, generics, params, returnType, annotations, body);
    }

    private String parseMethodName() {
        if (match(LBRACKET)) {
            String namespace = consume(IDENT, "expected symbol namespace").lexeme();
            consume(DOT, "expected '.' in symbol method");
            String symbol = consume(IDENT, "expected symbol name").lexeme();
            consume(RBRACKET, "expected ']' after symbol method");
            if (!namespace.equals("Symbol")) throw error(previous(), "symbol methods must use Symbol.<name>");
            return namespace + "." + symbol;
        }
        return consume(IDENT, "expected method name (methods omit 'fnc')").lexeme();
    }

    private Ast.TypeAliasDecl parseTypeAlias() {
        String name = consume(IDENT, "expected type alias name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(EQUAL, "expected '=' in type alias");
        Ast.TypeRef target = parseTypeRef();
        consumeStatementTerminator("type alias should end with ';'");
        return new Ast.TypeAliasDecl(name, generics, target);
    }

    private List<Ast.Annotation> parseAnnotations() {
        List<Ast.Annotation> result = new ArrayList<>();
        while (match(AT)) {
            String name = consume(IDENT, "expected annotation name").lexeme();
            List<Ast.TypeRef> args = new ArrayList<>();
            Token.Type close = null;
            if (match(LT)) close = GT;
            else if (match(LPAREN)) close = RPAREN;
            if (close != null) {
                if (!check(close)) do args.add(parseTypeRef()); while (match(COMMA));
                consume(close, "expected annotation terminator");
            }
            result.add(new Ast.Annotation(name, args));
        }
        return result;
    }

    private Modifiers parseModifiers() {
        Ast.Visibility visibility = Ast.Visibility.PRIVATE;
        boolean async = false;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean progress;
        do {
            progress = true;
            if (match(PUB)) visibility = Ast.Visibility.PUBLIC;
            else if (match(PRIVATE)) visibility = Ast.Visibility.PRIVATE;
            else if (match(ASYNC)) async = true;
            else if (match(STATIC)) isStatic = true;
            else if (match(ABSTRACT)) isAbstract = true;
            else progress = false;
        } while (progress);
        return new Modifiers(visibility, async, isStatic, isAbstract);
    }

    private Ast.TypeRef parseReturnType(List<Ast.Annotation> annotations) {
        Ast.TypeRef annotated = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                if (annotation.arguments().size() != 1) throw error(previous(), "@Ret requires exactly one type");
                annotated = annotation.arguments().getFirst();
            }
        }
        Ast.TypeRef arrow = match(FAT_ARROW) ? parseTypeRef() : null;
        if (annotated != null && arrow != null && !sameType(annotated, arrow)) {
            throw error(previous(), "@Ret type and => return type disagree");
        }
        return arrow != null ? arrow : annotated != null ? annotated : Ast.TypeRef.simple("void");
    }

    private boolean sameType(Ast.TypeRef a, Ast.TypeRef b) {
        return a.name().equals(b.name()) && a.arguments().equals(b.arguments()) && a.inferArguments() == b.inferArguments();
    }

    private List<String> parseGenericParameters() {
        if (!match(LT)) return List.of();
        List<String> names = new ArrayList<>();
        do names.add(consume(IDENT, "expected generic parameter name").lexeme()); while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return names;
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator) {
        return parseParametersUntil(terminator, java.util.Set.of());
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator, java.util.Set<String> annotationStructuralNames) {
        if (check(terminator)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = false;
            Ast.ParamMode mode = Ast.ParamMode.BORROW;

            // Ownership transfer stays contextual before the type. Mutable
            // access is canonical in type-first form: Type mut name. Keep the
            // older mut Type name spelling accepted during migration.
            if (match(MUT)) mode = Ast.ParamMode.MUT;
            else if (check(IDENT) && peek().lexeme().equals("take")) {
                advance();
                mode = Ast.ParamMode.TAKE;
            }

            // Name-first structural spelling: y structural Foo
            if (mode == Ast.ParamMode.BORROW && check(IDENT) && checkNextLexeme("structural")) {
                String name = advance().lexeme();
                Token marker = consume(IDENT, "expected structural");
                if (!marker.lexeme().equals("structural")) throw error(marker, "expected structural");
                Ast.TypeRef type = parseTypeRef();
                params.add(new Ast.Param(type, name, true, Ast.ParamMode.BORROW));
                continue;
            }

            // Function annotation spelling: @AllowStructural(y) fnc x(y Foo)
            if (mode == Ast.ParamMode.BORROW
                    && check(IDENT)
                    && annotationStructuralNames.contains(peek().lexeme())
                    && checkNext(IDENT)) {
                String name = advance().lexeme();
                Ast.TypeRef type = parseTypeRef();
                params.add(new Ast.Param(type, name, true, Ast.ParamMode.BORROW));
                continue;
            }

            // Existing type-first spelling: @Structural Foo y
            if (match(AT)) {
                String annotation = consume(IDENT, "expected parameter annotation").lexeme();
                if (!annotation.equals("Structural")) throw error(previous(), "only @Structural is currently supported on parameters");
                structural = true;
            }
            if (structural && mode != Ast.ParamMode.BORROW) {
                throw error(peek(), "structural parameters are read-only views and cannot use mut or take");
            }

            Ast.TypeRef type = parseTypeRef();
            if (match(MUT)) {
                if (mode == Ast.ParamMode.TAKE) {
                    throw error(previous(), "a take parameter owns its value and cannot also be a temporary mut reference");
                }
                if (structural) {
                    throw error(previous(), "structural parameters are read-only views and cannot use mut or take");
                }
                mode = Ast.ParamMode.MUT;
            }
            String name = consume(IDENT, "expected parameter name").lexeme();
            params.add(new Ast.Param(type, name, structural, mode));
        } while (match(COMMA));
        return params;
    }

    private java.util.Set<String> structuralAnnotationNames(List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("AllowStructural")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (!argument.arguments().isEmpty() || argument.inferArguments() || argument.isStringLiteral()) {
                    throw error(previous(), "@AllowStructural arguments must be parameter names");
                }
                allowed.add(argument.name());
            }
        }
        return java.util.Set.copyOf(allowed);
    }

    private List<Ast.Param> applyStructuralAnnotations(List<Ast.Param> params, List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>(structuralAnnotationNames(annotations));
        if (allowed.isEmpty()) return params;
        java.util.Set<String> found = new java.util.HashSet<>();
        List<Ast.Param> result = new ArrayList<>(params.size());
        for (Ast.Param param : params) {
            boolean structural = param.structural() || allowed.contains(param.name());
            if (structural && param.mode() != Ast.ParamMode.BORROW) {
                throw error(previous(), "@AllowStructural parameters are read-only and cannot use mut or take: " + param.name());
            }
            if (allowed.contains(param.name())) found.add(param.name());
            result.add(new Ast.Param(param.type(), param.name(), structural, param.mode()));
        }
        if (!found.equals(allowed)) {
            java.util.Set<String> missing = new java.util.HashSet<>(allowed);
            missing.removeAll(found);
            throw error(previous(), "@AllowStructural names unknown parameter(s): " + missing);
        }
        return List.copyOf(result);
    }

    private Ast.TypeRef parseTypeRef() {
        if (check(AMP)) {
            throw error(peek(), "pointer-style '&' borrow types are not part of Oreslang; use ordinary parameters, canonical 'Type mut name', or contextual 'take Type name'");
        }
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());

        if (match(TYPEOF)) {
            consume(FNC, "typeof function types use 'typeof fnc(...) -> ReturnType'");
            return parseFunctionTypeSignature();
        }

        if (check(LPAREN) && looksLikeFunctionType()) return parseFunctionTypeSignature();
        if (match(LPAREN)) {
            Ast.TypeRef grouped = parseTypeRef();
            consume(RPAREN, "expected ')' after grouped type");
            return grouped;
        }

        String name;
        if (match(VOID)) name = "void";
        else if (match(SELF)) name = "self";
        else if (match(NULL)) name = "null";
        else name = parseQualifiedName();

        List<Ast.TypeRef> args = new ArrayList<>();
        boolean infer = false;
        if (match(LT)) {
            if (match(GT)) infer = true;
            else {
                do args.add(parseTypeRef()); while (match(COMMA));
                consume(GT, "expected '>' after type arguments");
            }
        }
        return new Ast.TypeRef(name, args, infer);
    }

    private Ast.TypeRef parseFunctionTypeSignature() {
        consume(LPAREN, "expected '(' in function type");
        List<Ast.TypeRef> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                Ast.TypeRef paramType = parseTypeRef();
                if (check(IDENT)) advance(); // optional documentation-only parameter name
                params.add(paramType);
            } while (match(COMMA));
        }
        consume(RPAREN, "expected ')' after function type parameters");
        consume(ARROW, "function types use the slim arrow '->'");
        Ast.TypeRef result = parseTypeRef();
        return Ast.TypeRef.functionType(params, result);
    }

    private boolean looksLikeFunctionType() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private String parseQualifiedName() {
        StringBuilder name = new StringBuilder(consume(IDENT, "expected name").lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENT, "expected name after '.'").lexeme());
        return name.toString();
    }

    private List<Ast.Stmt> parseBlock() {
        consume(LBRACE, "expected '{'");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) body.add(parseStatement());
        consume(RBRACE, "expected '}'");
        return body;
    }

    private Ast.Stmt parseStatement() {
        if (isBindingKind(peek().type())) return parseBindingStatement();
        if (check(LBRACKET) && looksLikeDestructure()) return parseDestructure();
        if (match(RETURN)) {
            Ast.Expr value = check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeStatementTerminator("return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(DEFER)) {
            Ast.Expr expression = parseExpression();
            consumeStatementTerminator("defer statement should end with ';'");
            return new Ast.DeferStmt(expression);
        }
        if (match(IF)) return parseIf();
        if (match(MATCH)) return parseMatch();
        if (match(TRY)) return parseTry();
        if (match(FOR)) return parseFor();

        Ast.Expr expression = parseExpression();
        consumeStatementTerminator("expression statement should end with ';'");
        return new Ast.ExprStmt(expression);
    }

    private Ast.Stmt parseFor() {
        if (match(LPAREN)) return parseParenthesizedFor();

        // Canonical iterator forms:
        //   for item of values do ... done
        //   for [key, value] of entries do ... done
        //   for [val key, let value] of entries { ... }
        ForOfBindings parsed = parseForOfBindings();
        consume(OF, "expected 'of' in iterator loop");
        Ast.Expr iterable = parseExpression();
        return new Ast.ForOfStmt(
                parsed.bindings(),
                parsed.destructuringPattern(),
                iterable,
                parseLoopBody());
    }

    private Ast.Stmt parseParenthesizedFor() {
        if (looksLikeForOfHeader()) {
            ForOfBindings parsed = parseForOfBindings();
            consume(OF, "expected 'of' in for-of loop");
            Ast.Expr iterable = parseExpression();
            consume(RPAREN, "expected ')' after for-of header");
            return new Ast.ForOfStmt(
                    parsed.bindings(),
                    parsed.destructuringPattern(),
                    iterable,
                    parseLoopBody());
        }

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
            else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            Ast.Expr initializer = parseExpression();
            Ast.BindingStmt init = new Ast.BindingStmt(kind, type, name, initializer);
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseExpression();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        Ast.Stmt initializer = null;
        if (!check(SEMICOLON)) initializer = new Ast.ExprStmt(parseExpression());
        consume(SEMICOLON, "expected ';' after for initializer");
        Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
        consume(SEMICOLON, "expected ';' after for condition");
        Ast.Expr update = check(RPAREN) ? null : parseExpression();
        consume(RPAREN, "expected ')' after for header");
        return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
    }

    private boolean looksLikeForOfHeader() {
        if (check(IDENT)) return checkNext(OF);
        if (isBindingKind(peek().type())) {
            return current + 2 < tokens.size()
                    && tokens.get(current + 1).type() == IDENT
                    && tokens.get(current + 2).type() == OF;
        }
        if (!check(LBRACKET)) return false;

        int i = current + 1;
        boolean expectBinding = true;
        while (i < tokens.size()) {
            Token.Type type = tokens.get(i).type();
            if (type == RBRACKET) {
                return !expectBinding
                        && i + 1 < tokens.size()
                        && tokens.get(i + 1).type() == OF;
            }
            if (expectBinding) {
                if (isBindingKind(type)) {
                    i++;
                    if (i >= tokens.size() || tokens.get(i).type() != IDENT) return false;
                } else if (type != IDENT) {
                    return false;
                }
                expectBinding = false;
                i++;
                continue;
            }
            if (type == COMMA) {
                expectBinding = true;
                i++;
                continue;
            }
            return false;
        }
        return false;
    }

    private ForOfBindings parseForOfBindings() {
        if (!match(LBRACKET)) {
            Ast.BindingKind kind = isBindingKind(peek().type())
                    ? parseBindingKind()
                    : Ast.BindingKind.VAL;
            String name = consume(IDENT,
                    "expected iterator binding name or '[a, b]' destructuring pattern").lexeme();
            return new ForOfBindings(
                    List.of(new Ast.DestructureBinding(kind, name)),
                    false);
        }

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        if (check(RBRACKET)) throw error(peek(), "for-of destructuring pattern cannot be empty");
        do {
            Ast.BindingKind kind = isBindingKind(peek().type())
                    ? parseBindingKind()
                    : Ast.BindingKind.VAL;
            Token nameToken = consume(
                    IDENT,
                    "expected binding name in for-of destructuring pattern");
            String name = nameToken.lexeme();
            for (Ast.DestructureBinding existing : bindings) {
                if (existing.name().equals(name)) {
                    throw error(nameToken,
                            "duplicate binding '" + name + "' in for-of destructuring pattern");
                }
            }
            bindings.add(new Ast.DestructureBinding(kind, name));
        } while (match(COMMA));
        consume(RBRACKET, "expected ']' after for-of destructuring pattern");
        return new ForOfBindings(bindings, true);
    }

    private record ForOfBindings(
            List<Ast.DestructureBinding> bindings,
            boolean destructuringPattern) {
        private ForOfBindings {
            bindings = List.copyOf(bindings);
        }
    }

    private List<Ast.Stmt> parseLoopBody() {
        if (check(LBRACE)) return parseBlock();
        consume(DO, "expected '{' or 'do' after for loop header");
        List<Ast.Stmt> body = parseUntil(DONE);
        consume(DONE, "expected 'done' to close for loop");
        return body;
    }

    private Ast.BindingStmt parseBindingStatement() {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "binding requires initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("binding should end with ';'");
        return new Ast.BindingStmt(kind, type, name, initializer);
    }

    private Ast.DestructureStmt parseDestructure() {
        consume(LBRACKET, "expected '['");
        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        do {
            Ast.BindingKind kind = parseBindingKind();
            String name = consume(IDENT, "expected binding name in destructure").lexeme();
            bindings.add(new Ast.DestructureBinding(kind, name));
        } while (match(COMMA));
        consume(RBRACKET, "expected ']'");
        consume(EQUAL, "expected '=' after destructure pattern");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("destructure should end with ';'");
        return new Ast.DestructureStmt(bindings, initializer);
    }

    private boolean looksLikeDestructure() {
        return current + 1 < tokens.size() && isBindingKind(tokens.get(current + 1).type());
    }

    private Ast.IfStmt parseIf() {
        List<Ast.IfBranch> branches = new ArrayList<>();
        Ast.Expr condition = parseCondition();
        match(SEMICOLON);
        consume(DO, "expected 'do'");
        List<Ast.Stmt> body = parseUntil(ELSEIF, ELSE, FI);
        branches.add(new Ast.IfBranch(condition, body));

        while (match(ELSEIF)) {
            condition = parseCondition();
            match(SEMICOLON);
            consume(DO, "expected 'do'");
            body = parseUntil(ELSEIF, ELSE, FI);
            branches.add(new Ast.IfBranch(condition, body));
        }

        List<Ast.Stmt> elseBody = List.of();
        if (match(ELSE)) elseBody = parseUntil(FI);
        consume(FI, "expected 'fi' to close if");
        return new Ast.IfStmt(branches, elseBody);
    }

    private Ast.MatchStmt parseMatch() {
        Ast.Expr value = parseExpression();
        consume(LBRACE, "expected '{' after match value");

        List<Ast.MatchArm> arms = new ArrayList<>();
        boolean seenSome = false;
        boolean seenNone = false;

        while (!check(RBRACE) && !check(EOF)) {
            Token constructor = consume(IDENT, "Option match arms must be Some(name) or None");
            Ast.OptionPattern pattern;
            if (constructor.lexeme().equals("Some")) {
                if (seenSome) throw error(constructor, "duplicate Some arm in Option match");
                seenSome = true;
                consume(LPAREN, "Some pattern requires '('");
                String binding = consume(IDENT, "Some pattern requires a payload binding").lexeme();
                consume(RPAREN, "Some pattern requires ')'");
                pattern = new Ast.SomePattern(binding);
            } else if (constructor.lexeme().equals("None")) {
                if (seenNone) throw error(constructor, "duplicate None arm in Option match");
                seenNone = true;
                pattern = new Ast.NonePattern();
            } else {
                throw error(constructor, "Option match arms must be Some(name) or None");
            }

            consume(FAT_ARROW, "match arm requires '=>'");
            arms.add(new Ast.MatchArm(pattern, parseBlock()));
            match(COMMA);
        }

        consume(RBRACE, "expected '}' after match arms");
        if (!seenSome || !seenNone) {
            throw error(previous(), "Option match must be exhaustive with exactly one Some(...) arm and one None arm");
        }
        return new Ast.MatchStmt(value, arms);
    }

    private Ast.Expr parseCondition() {
        Ast.Expr expression = parseEquality();
        while (true) {
            if (match(COMMA)) expression = new Ast.BinaryExpr(",", expression, parseEquality());
            else if (match(PIPE)) expression = new Ast.BinaryExpr("|", expression, parseEquality());
            else break;
        }
        return expression;
    }

    private Ast.TryStmt parseTry() {
        List<Ast.Stmt> body = parseBlock();
        consume(CATCH, "expected catch after try block");
        consume(LPAREN, "expected '(' after catch");
        String error = consume(IDENT, "expected catch binding").lexeme();
        consume(RPAREN, "expected ')' after catch binding");
        List<Ast.Stmt> catchBody = parseBlock();
        List<Ast.Stmt> finallyBody = match(FINALLY) ? parseBlock() : List.of();
        return new Ast.TryStmt(body, error, catchBody, finallyBody);
    }

    private List<Ast.Stmt> parseUntil(Token.Type... terminators) {
        List<Ast.Stmt> body = new ArrayList<>();
        outer: while (!check(EOF)) {
            for (Token.Type terminator : terminators) if (check(terminator)) break outer;
            body.add(parseStatement());
        }
        return body;
    }

    public Ast.Expr parseExpression() { return parseAssignment(); }

    private Ast.Expr parseAssignment() {
        Ast.Expr expr = parseConditional();
        if (match(EQUAL)) {
            if (!(expr instanceof Ast.NameExpr) && !(expr instanceof Ast.MemberExpr) && !(expr instanceof Ast.IndexExpr)) {
                throw error(previous(), "assignment target must be a local, field, or index");
            }
            return new Ast.AssignExpr(expr, parseAssignment());
        }
        return expr;
    }

    private Ast.Expr parseConditional() {
        Ast.Expr condition = parseOr();
        if (!match(QUESTION)) return condition;
        Ast.Expr whenTrue = parseAssignment();
        consume(COLON, "expected ':' in ternary expression");
        Ast.Expr whenFalse = parseConditional();
        return new Ast.ConditionalExpr(condition, whenTrue, whenFalse);
    }

    private Ast.Expr parseOr() {
        Ast.Expr expr = parseEquality();
        while (match(PIPE)) expr = new Ast.BinaryExpr("|", expr, parseEquality());
        return expr;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr expr = parseComparison();
        while (match(EQUAL_EQUAL, BANG_EQUAL)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseComparison());
        }
        return expr;
    }

    private Ast.Expr parseComparison() {
        Ast.Expr expr = parseAdditive();
        while (match(LT, LTE, GT, GTE)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseAdditive());
        }
        return expr;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr expr = parseMultiplicative();
        while (match(PLUS, MINUS)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseMultiplicative());
        }
        return expr;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr expr = parseUnary();
        while (match(STAR, SLASH, PERCENT)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseUnary());
        }
        return expr;
    }

    private Ast.Expr parseUnary() {
        if (match(BANG, MINUS, PLUS)) return new Ast.UnaryExpr(previous().lexeme(), parseUnary());
        if (check(AMP)) {
            throw error(peek(), "pointer-style '&' borrow expressions are not part of Oreslang; calls pass references automatically, use rt borrow for an explicit stored read view");
        }
        if (match(RT)) return parseRuntimeExpression();
        if (match(AWAIT)) return new Ast.AwaitExpr(parseUnary());
        return parsePostfix();
    }

    private Ast.Expr parseRuntimeExpression() {
        Token operation = consume(IDENT, "expected runtime operation after 'rt'");
        String name = operation.lexeme();
        if (!name.equals("copy") && !name.equals("take")
                && !name.equals("borrow") && !name.equals("share")
                && !name.equals("ptr") && !name.equals("deref")) {
            throw error(operation, "unknown rt operation '" + name + "'");
        }

        Ast.Expr argument;
        if (match(LPAREN)) {
            argument = parseExpression();
            consume(RPAREN, "expected ')' after rt " + name + " argument");
        } else {
            // Command form has unary precedence. Parentheses widen the operand.
            argument = parseUnary();
        }

        // This compiler-only spelling cannot be produced by the guest lexer.
        String intrinsic = "$" + "rt" + "$" + name;
        return new Ast.CallExpr(new Ast.NameExpr(intrinsic), List.of(argument));
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (true) {
            if (match(LPAREN)) {
                List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, args);
            } else if (match(DOT)) {
                String member = consume(IDENT, "expected member name after '.'").lexeme();
                expr = new Ast.MemberExpr(expr, member);
            } else if (match(LBRACKET)) {
                Ast.Expr index = parseExpression();
                consume(RBRACKET, "expected ']' after index");
                expr = new Ast.IndexExpr(expr, index);
            } else break;
        }
        return expr;
    }

    private Ast.Expr parsePrimary() {
        if (match(INT)) return new Ast.LiteralExpr(Long.parseLong(previous().lexeme().replace("_", "")));
        if (match(FLOAT)) return new Ast.LiteralExpr(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(IMAG)) {
            String raw = previous().lexeme().substring(0, previous().lexeme().length() - 1).replace("_", "");
            return new Ast.LiteralExpr(new Ast.Imaginary(Double.parseDouble(raw)));
        }
        if (match(STRING)) return new Ast.LiteralExpr(previous().lexeme());
        if (match(TRUE)) return new Ast.LiteralExpr(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralExpr(Boolean.FALSE);
        if (match(NULL)) throw error(previous(), "standalone null values are forbidden; use Option<T>");
        if (match(SELF)) return new Ast.NameExpr("self");
        if (match(IDENT)) return new Ast.NameExpr(previous().lexeme());
        if (match(NEW)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after new type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after constructor arguments");
            return new Ast.NewExpr(type, args);
        }
        if (match(OBJ)) return parseObjectLiteral();
        if (match(ARR)) {
            consume(LBRACKET, "expected '[' after arr");
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']' after arr literal");
            return new Ast.ListExpr(items);
        }
        if (check(PIPE)) return parsePipeLambda();
        if (check(LPAREN) && looksLikeLambda()) return parseLambda();
        if (match(LPAREN)) {
            Ast.Expr first = parseExpression();
            if (match(COMMA)) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                do items.add(parseExpression()); while (match(COMMA));
                consume(RPAREN, "expected ')' after tuple");
                return new Ast.TupleExpr(items);
            }
            consume(RPAREN, "expected ')' after expression");
            return first;
        }
        if (match(LBRACKET)) {
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']'");
            return new Ast.ListExpr(items);
        }
        throw error(peek(), "expected expression");
    }

    private Ast.ObjectExpr parseObjectLiteral() {
        consume(LBRACE, "expected '{' after obj");
        List<Ast.ObjectField> fields = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                String name;
                if (match(IDENT, STRING)) name = previous().lexeme();
                else throw error(peek(), "expected object field name");
                consume(COLON, "expected ':' after object field name");
                fields.add(new Ast.ObjectField(name, parseExpression()));
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after obj literal");
        return new Ast.ObjectExpr(fields);
    }

    private Ast.LambdaExpr parseLambda() {
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after lambda parameters");
        consume(ARROW, "expected '->' after lambda parameters");
        if (!check(LBRACE)) throw error(peek(), "lambdas always require a block body; use '-> { ... }'");
        return new Ast.LambdaExpr(params, null, parseBlock());
    }

    private Ast.LambdaExpr parsePipeLambda() {
        consume(PIPE, "expected '|'");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(PIPE)) {
            do {
                if (check(IDENT) && (checkNext(COMMA) || checkNext(PIPE))) {
                    String name = advance().lexeme();
                    params.add(new Ast.Param(Ast.TypeRef.inferred(), name, false, Ast.ParamMode.BORROW));
                } else {
                    Ast.ParamMode mode = Ast.ParamMode.BORROW;
                    if (match(MUT)) mode = Ast.ParamMode.MUT;
                    else if (check(IDENT) && peek().lexeme().equals("take")) {
                        advance();
                        mode = Ast.ParamMode.TAKE;
                    }
                    Ast.TypeRef type = parseTypeRef();
                    if (match(MUT)) {
                        if (mode == Ast.ParamMode.TAKE) {
                            throw error(previous(), "a take lambda parameter owns its value and cannot also be a temporary mut reference");
                        }
                        if (mode == Ast.ParamMode.MUT) {
                            throw error(previous(), "duplicate mut on lambda parameter; canonical spelling is 'Type mut name'");
                        }
                        mode = Ast.ParamMode.MUT;
                    }
                    String name = consume(IDENT, "expected lambda parameter name").lexeme();
                    params.add(new Ast.Param(type, name, false, mode));
                }
            } while (match(COMMA));
        }
        consume(PIPE, "expected closing '|' after lambda parameters");
        consume(ARROW, "lambdas use the slim arrow '->'");
        if (!check(LBRACE)) throw error(peek(), "lambdas always require a block body; use '|args| -> { ... }'");
        return new Ast.LambdaExpr(params, null, parseBlock());
    }

    private boolean looksLikeLambda() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private List<Ast.Expr> parseArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do args.add(parseExpression()); while (match(COMMA));
        return args;
    }

    private void consumeMemberTerminator(Token.Type structuralTerminator, String message) {
        if (match(SEMICOLON) || check(structuralTerminator)) return;
        throw error(peek(), message);
    }

    private void consumeStatementTerminator(String message) {
        if (match(SEMICOLON) || isSafeStatementBoundary()) return;
        throw error(peek(), message);
    }

    private boolean isSafeStatementBoundary() {
        return check(RBRACE) || check(FI) || check(END) || check(DONE)
                || check(ELSE) || check(ELSEIF)
                || check(CATCH) || check(FINALLY) || check(EOF);
    }

    private Ast.BindingKind parseBindingKind() {
        if (match(CONST)) return Ast.BindingKind.CONST;
        if (match(VAL)) return Ast.BindingKind.VAL;
        if (match(LET)) return Ast.BindingKind.LET;
        throw error(peek(), "expected const, val, or let");
    }

    private boolean isBindingKind(Token.Type type) { return type == CONST || type == VAL || type == LET; }

    private boolean match(Token.Type... types) {
        for (Token.Type type : types) {
            if (check(type)) { advance(); return true; }
        }
        return false;
    }

    private Token consume(Token.Type type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(Token.Type type) { return peek().type() == type; }
    private boolean checkNext(Token.Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }
    private boolean checkNextLexeme(String lexeme) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals(lexeme);
    }
    private Token advance() { if (!check(EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }

    private IllegalArgumentException error(Token token, String message) {
        return new IllegalArgumentException("Oreslang parse error at " + token.line() + ":" + token.column() + ": " + message);
    }

    private record Modifiers(Ast.Visibility visibility, boolean async, boolean isStatic, boolean isAbstract) { }
}
