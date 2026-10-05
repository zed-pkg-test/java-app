package dev.oreslang;

import dev.oreslang.runtime.LinkedProgramRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SsrValidationTest {
    @Test
    void validatesNativeSsrPrograms() throws Exception {
        LinkedProgramRunner.validate(Path.of("ssr/examples/basic-page.ores"));
        LinkedProgramRunner.validate(Path.of("ssr/examples/htmx-fragment.ores"));
        LinkedProgramRunner.validate(Path.of("ssr/tests/render-contract.ores"));
    }
}
