package com.example.lineage.chunk;

import java.util.List;

/** Summary of one immutable startup scan of the configured package root. */
public record PackageScanStatistics(
        String rootDirectory,
        int scannedFiles,
        int readFiles,
        int chunkCount,
        int failedFiles,
        long elapsedMillis,
        List<String> errorSummary,
        int replacementDecodedFiles,
        List<String> warningSummary
) {
}
