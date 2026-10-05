package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeCollectionsStdlibTest {

    @Test
    void collectionSemanticsExecuteFromBundledOreslangSource() throws Exception {
        Path source = Files.createTempFile("ores-native-collections-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    import module collections from "std/collections";

                    pub routine main(): void {
                      let values = new collections.ArrayList<int>();
                      values.add(10);
                      values.add(20);
                      values.push(30);

                      stdio.println(values.size());
                      stdio.println(values.contains(20));
                      stdio.println(values.index_of(30));
                      stdio.println(values.pop().unwrap());
                      stdio.println(values.first().unwrap());

                      let table = new collections.Map<string, int>();
                      stdio.println(table.put("answer", 41).is_none());
                      stdio.println(table.put("answer", 42).unwrap());
                      stdio.println(table.get("answer").unwrap());
                      stdio.println(table.contains_key("answer"));
                      stdio.println(table.remove("answer").unwrap());
                      stdio.println(table.is_empty());

                      let unique = new collections.Set<int>();
                      stdio.println(unique.add(7));
                      stdio.println(unique.add(7));
                      stdio.println(unique.size());

                      let queue = new collections.Queue<int>();
                      queue.enqueue(1);
                      queue.enqueue(2);
                      stdio.println(queue.dequeue().unwrap());
                      queue.enqueue(3);
                      stdio.println(queue.dequeue().unwrap());
                      stdio.println(queue.dequeue().unwrap());
                      stdio.println(queue.is_empty());

                      let deque = new collections.Deque<int>();
                      deque.push_back(2);
                      deque.push_front(1);
                      deque.push_back(3);
                      stdio.println(deque.pop_front().unwrap());
                      stdio.println(deque.pop_back().unwrap());
                      stdio.println(deque.peek_front().unwrap());
                      stdio.println(deque.pop_front().unwrap());
                      stdio.println(deque.is_empty());
                      return;
                    }
                    """);

            IncrementalCompiler.BuildResult build = assertDoesNotThrow(
                    () -> LinkedProgramRunner.run(
                            source,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            out,
                            err));

            assertTrue(build.units().containsKey("std/collections.ores"));

            String errors = err.toString(StandardCharsets.UTF_8);
            List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
            assertEquals(List.of(
                    "3", "true", "2", "30", "10",
                    "true", "41", "42", "true", "42", "true",
                    "true", "false", "1",
                    "1", "2", "3", "true",
                    "1", "3", "2", "2", "true"),
                    lines,
                    () -> "unexpected native-collections output, stderr=" + errors);
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void functionalSequenceOperatorsAreEagerTypePreservingAndNonMutating() throws Exception {
        Path source = Files.createTempFile("ores-native-functional-collections-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            Files.writeString(source, """
                    import module collections from "std/collections";

                    fnc twice(int value): int {
                      return value * 2;
                    }

                    fnc is_even(int value): bool {
                      return value % 2 == 0;
                    }

                    fnc add_ints(int total, int value): int {
                      return total + value;
                    }

                    fnc add_float(float total, int value): float {
                      return total + value;
                    }

                    pub routine main(): void {
                      let list = new collections.List<int>();
                      list.add(1);
                      list.add(2);
                      list.add(3);
                      val collections.List<int> list_mapped = list.map(twice);
                      val collections.List<int> list_filtered = list.filter(is_even);
                      stdio.println(list.size());
                      stdio.println(list_mapped.get(2));
                      stdio.println(list_filtered.size());
                      stdio.println(list_filtered.get(0));
                      stdio.println(list.reduce(0, add_ints));
                      stdio.println(list.reduce(add_ints).unwrap());

                      let array_list = new collections.ArrayList<int>();
                      array_list.add(4);
                      array_list.add(5);
                      val collections.ArrayList<int> array_mapped = array_list.map(twice);
                      val collections.ArrayList<int> array_filtered = array_list.filter(is_even);
                      stdio.println(array_list.size());
                      stdio.println(array_mapped.get(1));
                      stdio.println(array_filtered.get(0));
                      stdio.println(array_list.reduce(0.5, add_float));

                      let vector = new collections.Vector<int>();
                      vector.add(6);
                      vector.add(7);
                      val collections.Vector<bool> vector_mapped = vector.map(is_even);
                      val collections.Vector<int> vector_filtered = vector.filter(is_even);
                      stdio.println(vector.size());
                      stdio.println(vector_mapped.get(0));
                      stdio.println(vector_filtered.get(0));
                      stdio.println(vector.reduce(add_ints).unwrap());

                      let empty = new collections.Vector<int>();
                      stdio.println(empty.reduce(add_ints).is_none());
                      return;
                    }
                    """);

            assertDoesNotThrow(() -> LinkedProgramRunner.run(
                    source,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err));

            String errors = err.toString(StandardCharsets.UTF_8);
            List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
            assertEquals(List.of(
                    "3", "6", "1", "2", "6", "6",
                    "2", "10", "4", "9.5",
                    "2", "true", "6", "13", "true"),
                    lines,
                    () -> "unexpected functional-collections output, stderr=" + errors);
        } finally {
            Files.deleteIfExists(source);
        }
    }

}
