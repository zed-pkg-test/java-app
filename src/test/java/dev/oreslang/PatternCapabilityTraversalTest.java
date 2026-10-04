package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Consolidated regression coverage salvaged from the older match/formal-methods
 * branches. The current language uses statement-form switch rather than the
 * discarded expression-form match syntax, but capability traversal must still
 * inspect every part of a pattern dispatch.
 */
final class PatternCapabilityTraversalTest {

    private static Ast.Expr processInfo() {
        return new Ast.MemberExpr(new Ast.NameExpr("process"), "context_id");
    }

    private static Ast.Program program(Ast.SwitchStmt statement) {
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "probe",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(statement));
        return new Ast.Program(List.of(new Ast.ModuleDecl("app", List.of(fn))));
    }

    private static void assertDenied(Ast.SwitchStmt statement) {
        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program(statement), IsolatePolicy.strictFaas()));
    }

    @Test
    void switchSubjectCannotHideCapabilityUse() {
        assertDenied(new Ast.SwitchStmt(
                processInfo(),
                List.of(new Ast.SwitchClause(
                        new Ast.WildcardPattern(),
                        null,
                        List.of(new Ast.ReturnStmt(null))))));
    }

    @Test
    void switchGuardCannotHideCapabilityUse() {
        assertDenied(new Ast.SwitchStmt(
                new Ast.LiteralExpr(true),
                List.of(new Ast.SwitchClause(
                        new Ast.WildcardPattern(),
                        processInfo(),
                        List.of(new Ast.ReturnStmt(null))))));
    }

    @Test
    void switchClauseBodyCannotHideCapabilityUse() {
        assertDenied(new Ast.SwitchStmt(
                new Ast.LiteralExpr(true),
                List.of(new Ast.SwitchClause(
                        new Ast.WildcardPattern(),
                        null,
                        List.of(
                                new Ast.ExprStmt(processInfo()),
                                new Ast.ReturnStmt(null))))));
    }
}
