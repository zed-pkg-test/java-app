package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves compiler-owned Oreslang standard-library units.
 *
 * <p>The {@code std/} namespace is deliberately virtual and read-only. Guest
 * source cannot shadow it with a filesystem file or inject an alternate unit
 * into the compiler source map. Standard-library behavior lives in bundled
 * {@code .ores} files; Java owns only deterministic resource resolution.</p>
 */
final class StandardLibraryResolver {
    private static final String STD_PREFIX = "std/";
    private static final String RESOURCE_PREFIX = "stdlib/";
    private static final int MAX_UNIT_BYTES = 1024 * 1024;

    private StandardLibraryResolver() { }

    static void augmentSources(LinkedHashMap<String, String> sources) {
        Set<String> callerUnits = Set.copyOf(sources.keySet());
        for (String unitId : callerUnits) {
            if (isStdUnit(unitId)) {
                throw new IllegalArgumentException(
                        "source unit id '" + unitId + "' is reserved for the Oreslang standard library");
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>(callerUnits);
        LinkedHashSet<String> visited = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            String unitId = queue.removeFirst();
            if (!visited.add(unitId)) continue;

            String source = sources.get(unitId);
            if (source == null) {
                throw new IllegalStateException("missing source text for unit '" + unitId + "'");
            }

            Ast.Program program = Parser.parse(source);
            for (Ast.ImportDecl imported : program.imports()) {
                String dependency = resolveStdImport(unitId, imported.path());
                if (dependency == null) continue;

                if (!sources.containsKey(dependency)) {
                    sources.put(dependency, loadBundledUnit(dependency));
                }
                queue.addLast(dependency);
            }
        }
    }

    static boolean isStdUnit(String unitId) {
        return unitId != null && unitId.replace('\\', '/').startsWith(STD_PREFIX);
    }

    private static String resolveStdImport(String importerUnitId, String importPath) {
        if (importPath == null || importPath.isBlank()) return null;
        if (importPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "Oreslang standard-library imports use Unix '/' separators");
        }

        if (importPath.startsWith(STD_PREFIX)) {
            return canonicalStdUnit(importPath);
        }

        if (!isStdUnit(importerUnitId)) return null;

        if (!importPath.startsWith(".")) {
            throw new IllegalArgumentException(
                    "standard-library unit '" + importerUnitId
                            + "' may import only relative stdlib units or absolute std/... units");
        }

        Path parent = Path.of(importerUnitId).getParent();
        Path resolved = (parent == null ? Path.of(importPath) : parent.resolve(importPath)).normalize();
        String normalized = resolved.toString().replace('\\', '/');
        if (!normalized.startsWith(STD_PREFIX)) {
            throw new IllegalArgumentException(
                    "standard-library import '" + importPath + "' escapes reserved std/");
        }
        return canonicalStdUnit(normalized);
    }

    private static String canonicalStdUnit(String rawPath) {
        if (rawPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "Oreslang standard-library imports use Unix '/' separators");
        }
        Path normalizedPath = Path.of(rawPath).normalize();
        if (normalizedPath.isAbsolute()) {
            throw new IllegalArgumentException("standard-library import must be relative to std/");
        }

        String normalized = normalizedPath.toString().replace('\\', '/');
        if (!normalized.startsWith(STD_PREFIX) || normalized.equals("std")) {
            throw new IllegalArgumentException(
                    "standard-library import '" + rawPath + "' escapes reserved std/");
        }
        if (!normalized.endsWith(".ores")) normalized += ".ores";

        String relative = normalized.substring(STD_PREFIX.length());
        if (relative.isBlank() || relative.startsWith("/") || relative.contains("/../")
                || relative.startsWith("../")) {
            throw new IllegalArgumentException("invalid standard-library unit '" + rawPath + "'");
        }
        return STD_PREFIX + relative;
    }

    private static String loadBundledUnit(String unitId) {
        String resource = RESOURCE_PREFIX + unitId.substring(STD_PREFIX.length());
        try (InputStream input = StandardLibraryResolver.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "unknown Oreslang standard-library import '" + withoutExtension(unitId) + "'");
            }
            byte[] bytes = input.readAllBytes();
            if (bytes.length > MAX_UNIT_BYTES) {
                throw new IllegalStateException(
                        "standard-library unit '" + unitId + "' exceeds " + MAX_UNIT_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "failed reading bundled Oreslang standard-library unit '" + unitId + "'",
                    failure);
        }
    }

    private static String withoutExtension(String unitId) {
        return unitId.endsWith(".ores")
                ? unitId.substring(0, unitId.length() - ".ores".length())
                : unitId;
    }
}
