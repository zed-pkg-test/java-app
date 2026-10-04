package dev.oreslang.runtime;

import java.util.Locale;

/**
 * Declares how the OresVM host/runtime is deployed and whether runtime guest
 * JIT optimization is available.
 *
 * <p>This is deliberately NOT the Oreslang source compilation target.
 * {@code Mode.AOT} means the host/runtime is ahead-of-time deployed (for
 * example as a Native Image). It does not claim that a loaded .ores program was
 * itself AOT-compiled. Guest source compilation strategy lives in
 * {@link dev.oreslang.compiler.OresCompiler.CompilationMode}.
 */
public record ExecutionProfile(Mode mode, Platform platform) {
    public enum Mode { AOT, JIT, HYBRID }
    public enum Platform { SERVER, WINDOWS, MACOS, LINUX, ANDROID, IOS }
    public enum GuestRuntimeMode { INTERPRETED, JIT }

    public ExecutionProfile {
        if (mode == null || platform == null) throw new IllegalArgumentException("mode/platform are required");
        if (platform == Platform.IOS && mode != Mode.AOT) {
            throw new IllegalArgumentException("iOS profile is AOT-only; hot reload uses interpreted guest source/IR, not executable-code JIT");
        }
    }

    public static ExecutionProfile serverJit() { return new ExecutionProfile(Mode.JIT, Platform.SERVER); }
    public static ExecutionProfile serverHybrid() { return new ExecutionProfile(Mode.HYBRID, Platform.SERVER); }
    public static ExecutionProfile mobileAot(Platform platform) {
        if (platform != Platform.ANDROID && platform != Platform.IOS) throw new IllegalArgumentException("mobile profile requires ANDROID or IOS");
        return new ExecutionProfile(Mode.AOT, platform);
    }

    public boolean hostAheadOfTime() { return mode == Mode.AOT || mode == Mode.HYBRID; }
    public boolean guestJitAllowed() { return mode == Mode.JIT || mode == Mode.HYBRID; }
    public GuestRuntimeMode guestRuntimeMode() {
        return guestJitAllowed() ? GuestRuntimeMode.JIT : GuestRuntimeMode.INTERPRETED;
    }
    public boolean supportsSourceHotReload() { return true; }

    public static ExecutionProfile parse(String mode, String platform) {
        return new ExecutionProfile(
                Mode.valueOf(mode.toUpperCase(Locale.ROOT)),
                Platform.valueOf(platform.toUpperCase(Locale.ROOT)));
    }
}
