package com.example.lineage.api;

import com.example.lineage.callgraph.ProcedureCallAnalysis;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/call-graph")
public class ProcedureCallGraphController {

    private final ProcedureCallGraphService callGraphService;

    public ProcedureCallGraphController(ProcedureCallGraphService callGraphService) {
        this.callGraphService = callGraphService;
    }

    @GetMapping
    public ProcedureCallAnalysis findEdges(
            @RequestParam(name = "package", required = false) String packageName,
            @RequestParam(required = false) String procedure
    ) {
        return callGraphService.findGraph(packageName, procedure);
    }
}
