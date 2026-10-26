package com.example.lineage.retrieval;

import com.example.lineage.chunk.PlsqlChunkingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline, no-LLM evaluation. Search queries are extracted only from each question's own text. */
class ChunkRetrievalEvaluationTest {

    private static final double MINIMUM_PACKAGE_FILE_RECALL = 0.90;
    private static final int RETRIEVAL_LIMIT = 100;
    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_$#]*)\\.([A-Z][A-Z0-9_$#]*)\\b"
    );
    private static final Pattern UPPER_IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9_$#]*\\b");
    private static final Set<String> NON_COLUMN_WORDS = Set.of(
            "CASE", "WHEN", "JOIN", "MERGE", "INSERT", "UPDATE", "SELECT", "FROM", "PROCEDURE", "FUNCTION"
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluatesQuestionsOfflineUsingOnlyExplicitQuestionTextAsRetrievalInput() throws IOException {
        JsonNode questions = objectMapper.readTree(PROJECT_ROOT.resolve("evaluation/test_questions.json").toFile());
        ChunkRetrievalService retrieval = new ChunkRetrievalService(
                new PlsqlChunkingService(PROJECT_ROOT.resolve("packages").toString(), 120));

        ObjectNode report = objectMapper.createObjectNode();
        report.put("evaluation", "offline_chunk_retrieval_from_question_text");
        report.put("llm_api_calls", 0);
        report.put("ground_truth_used_for_retrieval_query", false);
        report.put("minimum_package_file_recall", MINIMUM_PACKAGE_FILE_RECALL);
        ArrayNode questionReports = report.putArray("questions");

        int scoredQuestionCount = 0;
        int expectedFileCount = 0;
        int foundFileCount = 0;
        int decoyChecks = 0;
        int decoyFalsePositives = 0;
        double recallSum = 0;

        for (JsonNode question : questions) {
            String questionText = question.path("question").asText();
            String query = extractExplicitColumn(questionText);
            ObjectNode item = questionReports.addObject();
            item.put("question", questionText);
            if (query == null) {
                item.put("status", "missing_target");
                item.put("missing_target", true);
                item.put("evaluation_note", "No unambiguous column identifier appears in the question text; no retrieval was attempted.");
                item.putArray("retrieved_evidence");
                continue;
            }

            item.put("retrieval_query", query);
            item.put("missing_target", false);
            List<ChunkRetrievalResult> retrieved = retrieval.search(query, RETRIEVAL_LIMIT);
            Set<String> retrievedPackages = new LinkedHashSet<>();
            retrieved.forEach(result -> retrievedPackages.add(result.fileName()));
            ArrayNode evidence = item.putArray("retrieved_evidence");
            for (ChunkRetrievalResult chunk : retrieved) {
                ObjectNode source = evidence.addObject();
                source.put("fileName", chunk.fileName());
                source.put("packageName", chunk.packageName());
                source.put("procedureName", chunk.procedureName());
                source.put("startLine", chunk.startLine());
                source.put("endLine", chunk.endLine());
                source.put("chunkId", chunk.chunkId());
                ArrayNode edges = source.putArray("dependencyEdges");
                for (SqlColumnDependencyAnalyzer.ColumnDependency edge : chunk.dependencyEdges()) {
                    ObjectNode edgeJson = edges.addObject();
                    edgeJson.put("target", edge.targetIdentifier());
                    edgeJson.put("source", edge.sourceIdentifier());
                    edgeJson.put("packageName", edge.packageName());
                    edgeJson.put("procedureName", edge.procedureName());
                    edgeJson.put("fileName", edge.fileName());
                    edgeJson.put("startLine", edge.startLine());
                    edgeJson.put("endLine", edge.endLine());
                    edgeJson.put("evidenceLine", edge.evidenceLine());
                }
            }

            boolean negativeDecoy = questionText.toUpperCase().contains("ARCHIVE");
            if (negativeDecoy) {
                decoyChecks++;
                boolean falsePositive = !retrieved.isEmpty();
                if (falsePositive) decoyFalsePositives++;
                item.put("status", falsePositive ? "false_positive" : "correct_no_dependency_match");
                item.put("false_positive", falsePositive);
                item.put("expected_answer", question.path("expected_answer").asText());
                item.put("evaluation_note", "Qualified archive lookup is checked as a negative decoy; it is excluded from positive package recall.");
                continue;
            }

            Set<String> expectedPackages = new LinkedHashSet<>();
            question.path("expected_source_files").forEach(source -> {
                String file = source.asText().replace('\\', '/');
                if (file.matches("(?i).*\\.pk[bs]$")) expectedPackages.add(file.substring(file.lastIndexOf('/') + 1));
            });
            Set<String> foundExpected = new LinkedHashSet<>(expectedPackages);
            foundExpected.retainAll(retrievedPackages);
            Set<String> missingExpected = new LinkedHashSet<>(expectedPackages);
            missingExpected.removeAll(foundExpected);
            Set<String> unexpectedRetrieved = new LinkedHashSet<>(retrievedPackages);
            unexpectedRetrieved.removeAll(expectedPackages);
            double recall = expectedPackages.isEmpty() ? 1.0 : (double) foundExpected.size() / expectedPackages.size();
            scoredQuestionCount++;
            expectedFileCount += expectedPackages.size();
            foundFileCount += foundExpected.size();
            recallSum += recall;

            item.put("status", "scored");
            item.put("expected_answer", question.path("expected_answer").asText());
            item.put("package_file_recall", recall);
            addStrings(item.putArray("expected_package_files"), expectedPackages);
            addStrings(item.putArray("found_expected_package_files"), foundExpected);
            addStrings(item.putArray("missing_expected_package_files"), missingExpected);
            addStrings(item.putArray("unexpected_retrieved_package_files"), unexpectedRetrieved);
        }

        double microRecall = expectedFileCount == 0 ? 1.0 : (double) foundFileCount / expectedFileCount;
        double macroRecall = scoredQuestionCount == 0 ? 1.0 : recallSum / scoredQuestionCount;
        report.put("question_count", questions.size());
        report.put("scored_positive_questions", scoredQuestionCount);
        report.put("expected_package_file_count", expectedFileCount);
        report.put("found_expected_package_file_count", foundFileCount);
        report.put("micro_package_file_recall", microRecall);
        report.put("macro_question_recall", macroRecall);
        report.put("archive_decoy_checks", decoyChecks);
        report.put("archive_decoy_false_positives", decoyFalsePositives);
        report.put("passed", microRecall >= MINIMUM_PACKAGE_FILE_RECALL
                && macroRecall >= MINIMUM_PACKAGE_FILE_RECALL && decoyFalsePositives == 0);

        Path reportFile = PROJECT_ROOT.resolve("evaluation/retrieval_evaluation_report.json");
        Files.writeString(reportFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator());

        assertThat(questions.size()).isEqualTo(12);
        assertThat(microRecall).as("positive-target package recall (%s)", reportFile)
                .isGreaterThanOrEqualTo(MINIMUM_PACKAGE_FILE_RECALL);
        assertThat(macroRecall).as("mean positive-target recall (%s)", reportFile)
                .isGreaterThanOrEqualTo(MINIMUM_PACKAGE_FILE_RECALL);
        assertThat(decoyFalsePositives).as("archive-name false positives (%s)", reportFile).isZero();
        System.out.printf("Offline Retrieval evaluation: %d questions, %d scored, micro recall %.1f%%, macro recall %.1f%%, archive false positives %d. Report: %s%n",
                questions.size(), scoredQuestionCount, microRecall * 100, macroRecall * 100, decoyFalsePositives, reportFile);
    }

    private String extractExplicitColumn(String question) {
        Matcher qualified = QUALIFIED_IDENTIFIER.matcher(question);
        while (qualified.find()) {
            String qualifier = qualified.group(1);
            if (!qualifier.toUpperCase().startsWith("PKG_")) {
                return qualifier.toUpperCase() + "." + qualified.group(2).toUpperCase();
            }
        }
        Matcher identifiers = UPPER_IDENTIFIER.matcher(question);
        List<String> candidates = new ArrayList<>();
        while (identifiers.find()) {
            String candidate = identifiers.group().toUpperCase();
            if (NON_COLUMN_WORDS.contains(candidate) || candidate.startsWith("PKG_")
                    || candidate.startsWith("RUN_") || candidate.matches("(?:SRC|TGT|STG)_[A-Z0-9_$#]+")) {
                continue;
            }
            candidates.add(candidate);
        }
        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    private void addStrings(ArrayNode target, Iterable<String> values) {
        values.forEach(target::add);
    }
}
