package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class RxCallbacksPointerlessValidationTest {
    private static final Path ROOT = Path.of("validation", "rx-callbacks");

    @Test
    void callbackLibraryUsesPointerlessOwnershipAndExecutes() throws Exception {
        String source = Files.readString(ROOT.resolve("src/rx.ores"));
        String operators = Files.readString(ROOT.resolve("tests/operators.ores"));

        assertFalse(source.contains("Fnc<&"), "callback types must not use &T");
        assertFalse(source.contains("&mut"), "callback source must not use &mut");
        assertFalse(Pattern.compile("(^|[\\(\\[,=])\\s*&\\s*[A-Za-z_(]", Pattern.MULTILINE)
                .matcher(source).find(), "callback source must not use unary address-of");
        assertFalse(operators.contains("Fnc<&"), "operator fixture must not use &T");
        assertFalse(Pattern.compile("(^|[\\(\\[,=])\\s*&\\s*[A-Za-z_(]", Pattern.MULTILINE)
                .matcher(operators).find(), "operator fixture must not use unary address-of");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                ROOT.resolve("tests/operators.ores"),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                err);

        assertEquals(
                Files.readString(ROOT.resolve("tests/operators.out")).strip(),
                out.toString(StandardCharsets.UTF_8).strip(),
                err.toString(StandardCharsets.UTF_8));
    }
}
