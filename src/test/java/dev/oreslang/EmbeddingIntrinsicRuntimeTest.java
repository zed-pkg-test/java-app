package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EmbeddingIntrinsicRuntimeTest {
    @Test
    void embeddingDotAndTopKUseIntrinsicFastPaths() throws Exception {
        String output = run("""
                define class EmbeddingBatch as
                  pub val int rows;
                  pub val int dimensions;
                  pub val List<float> values;
                  pub val bool normalized;
                  pub val String model_id;
                  pub val String model_revision;
                end

                @Intrinsic(EmbeddingDotSimilarity)
                fnc dot_similarity_into(
                  &EmbeddingBatch queries,
                  &EmbeddingBatch corpus,
                  List<float> mut output
                ): Result<List<float>, String> {
                  return Err("fallback dot body executed");
                }

                @Intrinsic(EmbeddingTopKDot)
                fnc top_k_dot_into(
                  &EmbeddingBatch queries,
                  &EmbeddingBatch corpus,
                  int k,
                  List<int> mut top_indices,
                  List<float> mut top_scores
                ): Result<bool, String> {
                  return Err("fallback top-k body executed");
                }

                @Intrinsic(UnknownNumericHint)
                fnc ordinary_fallback(): int {
                  return 7;
                }

                pub routine main(): void {
                  val EmbeddingBatch queries = new EmbeddingBatch(
                    1,
                    2,
                    arr[1.0, 0.0],
                    true,
                    "test-model",
                    "v1"
                  );

                  val EmbeddingBatch corpus = new EmbeddingBatch(
                    3,
                    2,
                    arr[
                      1.0, 0.0,
                      0.0, 1.0,
                      0.0 - 1.0, 0.0
                    ],
                    true,
                    "test-model",
                    "v1"
                  );

                  val List<float> similarities = arr[0.0, 0.0, 0.0];
                  val Result<List<float>, String> dot_result =
                    dot_similarity_into(&queries, &corpus, similarities);
                  val List<float> dots = dot_result.expect("dot intrinsic failed");

                  val List<int> indices = arr[0, 0];
                  val List<float> scores = arr[0.0, 0.0];
                  top_k_dot_into(&queries, &corpus, 2, indices, scores)
                    .expect("top-k intrinsic failed");

                  stdio.stdout.write(dots[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(dots[1]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(dots[2]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(indices[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(indices[1]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(scores[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(scores[1]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(ordinary_fallback());
                  return;
                }
                """);

        assertEquals("1.0:0.0:-1.0:0:1:1.0:0.0:7", output);
    }

    @Test
    void meanPoolingIntrinsicSkipsPaddingAndCanNormalize() throws Exception {
        String output = run("""
                define class TokenEmbeddingBatch as
                  pub val int rows;
                  pub val int tokens_per_row;
                  pub val int dimensions;
                  pub val List<float> values;
                  pub val List<bool> attention_mask;
                  pub val String model_id;
                  pub val String model_revision;
                end

                @Intrinsic(EmbeddingMeanPool)
                fnc mean_pool_token_embeddings_into(
                  &TokenEmbeddingBatch batch,
                  List<float> mut output,
                  bool normalize_output
                ): Result<List<float>, String> {
                  return Err("fallback mean-pool body executed");
                }

                pub routine main(): void {
                  val TokenEmbeddingBatch tokens = new TokenEmbeddingBatch(
                    2,
                    3,
                    2,
                    arr[
                      1.0, 2.0,
                      3.0, 4.0,
                      100.0, 100.0,
                      3.0, 4.0,
                      0.0, 0.0,
                      0.0, 0.0
                    ],
                    arr[
                      true, true, false,
                      true, false, false
                    ],
                    "test-model",
                    "v1"
                  );

                  val List<float> mean_output = arr[0.0, 0.0, 0.0, 0.0];
                  val List<float> means =
                    mean_pool_token_embeddings_into(&tokens, mean_output, false)
                      .expect("mean pool intrinsic failed");

                  val List<float> normalized_output = arr[0.0, 0.0, 0.0, 0.0];
                  val List<float> normalized =
                    mean_pool_token_embeddings_into(&tokens, normalized_output, true)
                      .expect("normalized mean pool intrinsic failed");

                  stdio.stdout.write(means[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(means[1]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(means[2]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(means[3]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(normalized[2]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(normalized[3]);
                  return;
                }
                """);

        assertEquals("2.0:3.0:3.0:4.0:0.6:0.8", output);
    }

    @Test
    void normalizationIntrinsicPreservesResultErrors() throws Exception {
        String output = run("""
                define class EmbeddingBatch as
                  pub val int rows;
                  pub val int dimensions;
                  pub val List<float> values;
                  pub val bool normalized;
                  pub val String model_id;
                  pub val String model_revision;
                end

                @Intrinsic(EmbeddingNormalizeRows)
                fnc normalize_embedding_rows_into(
                  &EmbeddingBatch batch,
                  List<float> mut output
                ): Result<List<float>, String> {
                  return Err("fallback normalize body executed");
                }

                pub routine main(): void {
                  val EmbeddingBatch valid = new EmbeddingBatch(
                    1,
                    2,
                    arr[3.0, 4.0],
                    false,
                    "test-model",
                    "v1"
                  );
                  val List<float> normalized_output = arr[0.0, 0.0];
                  val List<float> normalized =
                    normalize_embedding_rows_into(&valid, normalized_output)
                      .expect("normalize intrinsic failed");

                  val EmbeddingBatch zero = new EmbeddingBatch(
                    1,
                    2,
                    arr[0.0, 0.0],
                    false,
                    "test-model",
                    "v1"
                  );
                  val List<float> zero_output = arr[0.0, 0.0];
                  val Result<List<float>, String> zero_result =
                    normalize_embedding_rows_into(&zero, zero_output);

                  stdio.stdout.write(normalized[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(normalized[1]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(zero_result.is_err());
                  return;
                }
                """);

        assertEquals("0.6:0.8:true", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "embedding-intrinsic.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
