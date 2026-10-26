package com.example.lineage.api;

import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/retrieval")
public class ChunkRetrievalController {

    private final ChunkRetrievalService retrievalService;

    public ChunkRetrievalController(ChunkRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @GetMapping("/search")
    public List<ChunkRetrievalResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return retrievalService.search(query, limit);
    }
}
