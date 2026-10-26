package com.example.lineage.api;

import com.example.lineage.chunk.ChunkSummary;
import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/chunks")
public class ChunkController {

    private final PlsqlChunkingService chunkingService;

    public ChunkController(PlsqlChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    @GetMapping
    public List<ChunkSummary> listChunks() {
        return chunkingService.listChunks();
    }

    @GetMapping("/{chunkId}")
    public PlsqlChunk getChunk(@PathVariable String chunkId) {
        return chunkingService.getChunk(chunkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found"));
    }
}
