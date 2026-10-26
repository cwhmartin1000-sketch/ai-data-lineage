package com.example.lineage.api;

import com.example.lineage.assistant.AskRequest;
import com.example.lineage.assistant.AskResponse;
import com.example.lineage.assistant.LineageAssistantService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class LineageAssistantController {

    private final LineageAssistantService assistantService;

    public LineageAssistantController(LineageAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return assistantService.ask(request);
    }
}
