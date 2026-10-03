package dev.oreslang.launcher;

import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OresMain {
    private OresMain() { }

    private static OutputStream forwardingStream(OutputStream delegate) {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                delegate.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                delegate.write(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }
        };
    }

    public static void main(String[] args) throws Exception {
        boolean strict = false;
        String mode = "jit";
        String platform = "server";
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        String filename = null;

        for (String arg : args) {
            if (arg.equals("--strict-isolate")) strict = true;
            else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
            else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
            else if (arg.startsWith("--allow=")) {
                String raw = arg.substring("--allow=".length());
                if (!raw.isBlank()) {
                    for (String value : raw.split(",")) {
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                    }
                }
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else if (filename == null) filename = arg;
            else throw new IllegalArgumentException("only one .ores file may be supplied");
        }

        if (filename == null) {
            System.err.println("usage: ores [--strict-isolate] [--mode=aot|jit|hybrid] [--platform=server|windows|macos|linux|android|ios] [--allow=CAP,...] <file.ores>");
            System.exit(2);
            return;
        }

        Path path = Path.of(filename);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        // Graal's UNTRUSTED sandbox rejects the JVM's standard streams as
        // direct guest sinks. Preserve normal CLI behavior through distinct
        // forwarding streams so output is explicitly redirected while host
        // ownership of System.out/System.err remains outside the guest.
        Context.Builder builder = policy.restrictedContextBuilder(profile)
                .out(forwardingStream(System.out))
                .err(forwardingStream(System.err));
        Source source = Source.newBuilder(OresLanguage.ID, new File(filename))
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = builder.build()) {
            context.eval(source);
        }
    }
}
