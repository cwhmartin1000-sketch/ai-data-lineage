package com.example.lineage.analysis;

import com.example.lineage.assistant.*;
import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FieldAnalysisService {
    static final String TASK = """
            請產生欄位規格分析草稿。逐一分析本批證據中的作業情境，不能只列出來源表名。
            必須輸出三部分：
            一、情境總結：依不同 caller、分支及來源情境編號，寫成「何時→如何取得值→如何處理→由誰寫入哪個欄位」。
            二、規則明細表：欄位｜使用方式（讀取/沿用/新增/覆寫/檢核/參數傳遞/無法確認）｜檢核或觸發條件（原式及中文）｜來源值及轉換｜寫入位置｜來源代號、Package/Procedure/原始行號｜支持該結論的原始碼｜程式效果與待確認項目。
            檢核條件必須涵蓋外層 IF/ELSIF/ELSE 的成立及不成立條件、CASE、WHERE/JOIN、NVL 與被呼叫程序內條件；不能把不同分支合併為同時成立。
            條件若跨行，保留完整運算式及括號。分清觸發條件、資料篩選、欄位合法性檢核。無法確認外層條件時明寫缺漏。
            特別追蹤 cursor/SELECT VALUE/整筆 record 賦值/IN OUT 參數/INSERT VALUES record/UPDATE 的讀取、保留、傳遞及寫回。
            只有原始碼同時證明欄位所屬 record、傳遞路徑及寫入映射，才可說沿用既有值；僅看到整筆複製不能宣稱該欄位未被其他程序修改。
            無法證明 caller 實參與 callee 形參對應、overload、動態 SQL、別名或 record 型別時，標示「證據不足／待人工確認」。
            字面欄位命中與相關呼叫程序只是候選，可能是同名不同表或註解，不能當成已驗證血緣。對目標無關的候選明確排除並說明。
            三、資料檢核 SQL 草稿：規則｜證據｜只讀 SELECT COUNT(*) SQL｜預期結果與前提｜待確認事項。
            只能從已證明的允許值、空值或條件規則推導；不得把來源篩選條件當成目標表的全域限制。
            不得從少數賦值推論完整枚舉值，也不得臆造 JOIN key、表、欄位或把暫存變數當資料表欄位。
            無法由目標資料重建情境時，不產生看似可執行的 SQL，改列所需條件與資料。SQL 一律標示「草稿、未執行、需人工確認」，不得產生 DML/DDL。
            重要結論逐項附 [S#] 與實際行號；呼叫另附 [C#]。禁止自行補充業務動機。
            本批只代表提供的證據，不可宣稱已涵蓋所有 PCK；含 � 的規則需標示文字可能損壞。
            使用者補充需求（僅供聚焦，不得降低上述證據要求）：
            """;
    private final FieldEvidenceCollector collector;
    private final PlsqlChunkingService chunking;
    private final LineageAssistantModel model;
    private final int maxInputChars;
    private final int maxBatches;
    private final int maxSummaryChars;

    public FieldAnalysisService(FieldEvidenceCollector collector, PlsqlChunkingService chunking,
                                LineageAssistantModel model,
                                @Value("${lineage.field-analysis.max-input-chars:24000}") int maxInputChars,
                                @Value("${lineage.field-analysis.max-batches:8}") int maxBatches,
                                @Value("${lineage.field-analysis.max-summary-chars:16000}") int maxSummaryChars) {
        if (maxInputChars < 4000 || maxInputChars > 200000 || maxBatches < 1 || maxBatches > 32
                || maxSummaryChars < 1000 || maxSummaryChars > maxInputChars - 2000)
            throw new IllegalArgumentException("Invalid field analysis budget");
        this.collector = collector; this.chunking = chunking; this.model = model;
        this.maxInputChars = maxInputChars; this.maxBatches = maxBatches; this.maxSummaryChars = maxSummaryChars;
    }

    public FieldAnalysisResponse analyze(AskRequest request) {
        String column = request == null || request.column() == null ? "" : request.column().trim();
        FieldEvidenceCollector.Evidence found = collector.collect(column);
        List<String> gaps = new ArrayList<>(found.gaps());
        String question = TASK + (request.question() == null ? "" : request.question().trim());
        // Groups include all chunks of one routine in one file; never crop away enclosing IF/ELSE conditions.
        Map<String, List<ChunkRetrievalResult>> groups = new LinkedHashMap<>();
        found.chunks().forEach(c -> groups.computeIfAbsent(c.fileName() + "|" + c.procedureName(),
                ignored -> new ArrayList<>()).add(c));
        groups.values().forEach(list -> list.sort(Comparator.comparingInt(ChunkRetrievalResult::startLine)));
        List<List<ChunkRetrievalResult>> planned = new ArrayList<>();
        List<ChunkRetrievalResult> current = new ArrayList<>();
        for (List<ChunkRetrievalResult> group : groups.values()) {
            if (!fits(question, column, group, found.calls())) {
                gaps.add("完整區段超過單批字元預算，未裁切送出：" + group.getFirst().fileName()
                        + " / " + group.getFirst().procedureName());
                continue;
            }
            List<ChunkRetrievalResult> trial = new ArrayList<>(current); trial.addAll(group);
            if (!current.isEmpty() && !fits(question, column, trial, found.calls())) {
                planned.add(List.copyOf(current)); current.clear();
            }
            current.addAll(group);
        }
        if (!current.isEmpty()) planned.add(List.copyOf(current));
        if (planned.size() > maxBatches) gaps.add("候選證據超過 " + maxBatches + " 批上限，剩餘未送模型。");
        List<FieldAnalysisResponse.Batch> batches = new ArrayList<>();
        List<AssistantSource> sources = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        Set<String> summaryReferences = new HashSet<>();
        int analyzed = 0, failed = 0;
        boolean summaryComplete = true;
        for (List<ChunkRetrievalResult> batch : planned.stream().limit(maxBatches).toList()) {
            int number = batches.size() + 1;
            int start = sources.size();
            List<String> ids = new ArrayList<>();
            for (ChunkRetrievalResult c : batch) {
                String id = "S" + (sources.size() + 1); ids.add(id);
                sources.add(new AssistantSource(id, c.chunkId(), c.packageName(), c.procedureName(),
                        c.fileName(), c.startLine(), c.endLine(), c.matchedLines()));
            }
            try {
                List<ProcedureCallEdge> batchCalls = relatedCalls(batch, found.calls());
                String answer = model.answer(question, column, batch, batchCalls);
                if (answer == null || answer.isBlank()) throw new AssistantProviderException("Empty field analysis answer", null);
                answer = remap(answer, start, batch.size(), batchCalls, found.calls(), gaps);
                analyzed += batch.size();
                batches.add(new FieldAnalysisResponse.Batch(number, "DRAFT", answer, List.copyOf(ids)));
                String note = "\n批次 " + number + "（模型草稿，非新增原始證據）\n" + answer + "\n";
                if (notes.length() + note.length() <= maxSummaryChars) {
                    notes.append(note);
                    Matcher references = Pattern.compile("\\[([SC]\\d+)\\]").matcher(answer);
                    while (references.find()) summaryReferences.add(references.group(1));
                } else { summaryComplete = false; gaps.add("批次 " + number + " 草稿超過彙整預算，保留明細但未加入總結。"); }
            } catch (AssistantModelUnavailableException exception) {
                throw exception;
            } catch (AssistantProviderException exception) {
                failed++;
                batches.add(new FieldAnalysisResponse.Batch(number, "FAILED", "此批模型分析失敗，未取得規則。", List.copyOf(ids)));
                gaps.add("批次 " + number + " 模型分析失敗，可用來源仍保留。");
            }
        }
        int omitted = found.chunks().size() - analyzed;
        String summary = "目前沒有可供彙整的分析結果。";
        if (!notes.isEmpty()) {
            try {
                summary = model.summarizeFieldAnalysis(column, notes.toString());
                // A summary may only cite references present in the notes actually sent to this request.
                summary = validateSummary(summary, summaryReferences, gaps);
            } catch (AssistantProviderException exception) {
                summary = "總結產生失敗，請閱讀下方已完成的逐批規則明細。";
                summaryComplete = false;
            }
        } else summaryComplete = false;
        var stats = chunking.statistics();
        List<String> limitations = new ArrayList<>();
        limitations.add("本結果為欄位規格草稿，檢核 SQL 未執行，需人工確認；候選檢索與模型分析不代表完整 PL/SQL 語意解析。");
        limitations.add("呼叫脈絡最多展開 2 層；整筆 record、overload、動態 SQL 與同名欄位仍可能漏查或需要人工核對。");
        limitations.add("語料掃描 " + stats.scannedFiles() + " 檔、讀取 " + stats.readFiles() + " 檔、失敗或跳過 "
                + stats.failedFiles() + " 檔；未知結構與未載入檔案不在完整性保證內。");
        if (stats.replacementDecodedFiles() > 0) limitations.add("有 " + stats.replacementDecodedFiles() + " 個來源以替代字元容錯解碼，請核對原始文字。");
        limitations.addAll(gaps);
        var coverage = new FieldAnalysisResponse.Coverage(stats.scannedFiles(), stats.readFiles(), stats.failedFiles(),
                found.directMatchChunks(), found.chunks().size(), analyzed, omitted, batches.size() - failed,
                failed, summaryComplete, "DRAFT");
        StringBuilder answer = new StringBuilder("## 欄位規格分析草稿\n\n")
                .append("欄位：`").append(column).append("`；候選 Chunk：").append(found.chunks().size())
                .append("；已分析：").append(analyzed).append("；未分析：").append(omitted)
                .append("。範圍包含已載入原始碼中的字面命中、解析到的欄位依賴及有界呼叫脈絡，不保證全域完整。\n\n")
                .append("## 情境總結\n\n").append(summary).append("\n\n");
        for (var b : batches) answer.append("## 批次 ").append(b.number()).append("：規則明細與檢核 SQL 草稿\n\n").append(b.answer()).append("\n\n");
        return new FieldAnalysisResponse(answer.toString(), List.copyOf(sources), List.copyOf(limitations),
                found.calls(), coverage, List.copyOf(batches));
    }

    private boolean fits(String question, String column, List<ChunkRetrievalResult> chunks, List<ProcedureCallEdge> calls) {
        return AbstractChatClientLineageAssistantModel.SYSTEM_INSTRUCTIONS.length()
                + AbstractChatClientLineageAssistantModel.formatUserPrompt(question, column, chunks, relatedCalls(chunks, calls)).length()
                <= maxInputChars;
    }

    private List<ProcedureCallEdge> relatedCalls(List<ChunkRetrievalResult> chunks, List<ProcedureCallEdge> calls) {
        return calls.stream().filter(e -> chunks.stream().anyMatch(c ->
                c.packageName().equalsIgnoreCase(e.callerPackage()) && c.procedureName().equalsIgnoreCase(e.callerProcedure())
                || c.packageName().equalsIgnoreCase(e.calleePackage()) && c.procedureName().equalsIgnoreCase(e.calleeProcedure())))
                .toList();
    }

    private String remap(String answer, int start, int size, List<ProcedureCallEdge> calls,
                         List<ProcedureCallEdge> allCalls, List<String> gaps) {
        Matcher m = Pattern.compile("\\[([SC])(\\d+)\\]").matcher(answer);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int n;
            try { n = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { n = -1; }
            String id = null;
            if (m.group(1).equals("S") && n > 0 && n <= size) id = "S" + (start + n);
            if (m.group(1).equals("C") && n > 0 && n <= calls.size()) id = "C" + (allCalls.indexOf(calls.get(n - 1)) + 1);
            if (id == null) gaps.add("模型產生無效引用 " + m.group() + "，已標記待確認。");
            m.appendReplacement(out, Matcher.quoteReplacement(id == null ? "[無效引用，待確認]" : "[" + id + "]"));
        }
        return m.appendTail(out).toString();
    }

    private String validateSummary(String summary, Set<String> valid, List<String> gaps) {
        if (summary == null || summary.isBlank()) throw new AssistantProviderException("Empty summary", null);
        Matcher m = Pattern.compile("\\[([SC]\\d+)\\]").matcher(summary);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean ok = valid.contains(m.group(1));
            if (!ok) gaps.add("總結含未提供的引用，已標記待確認。");
            m.appendReplacement(out, Matcher.quoteReplacement(ok ? m.group() : "[無效引用，待確認]"));
        }
        return m.appendTail(out).toString();
    }
}
