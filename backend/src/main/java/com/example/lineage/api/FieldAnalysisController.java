package com.example.lineage.api;

import com.example.lineage.analysis.FieldAnalysisResponse;
import com.example.lineage.analysis.FieldAnalysisService;
import com.example.lineage.assistant.AskRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class FieldAnalysisController {
    private final FieldAnalysisService service;
    public FieldAnalysisController(FieldAnalysisService service) { this.service = service; }

    @PostMapping("/analyze-field")
    public FieldAnalysisResponse analyze(@RequestBody AskRequest request) { return service.analyze(request); }
}
