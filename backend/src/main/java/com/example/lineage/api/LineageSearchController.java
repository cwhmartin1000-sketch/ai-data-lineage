package com.example.lineage.api;

import com.example.lineage.search.LineageSearchResult;
import com.example.lineage.search.LineageSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/lineage")
public class LineageSearchController {

    private final LineageSearchService searchService;

    public LineageSearchController(LineageSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<LineageSearchResult> search(@RequestParam String column) {
        return searchService.search(column);
    }
}
