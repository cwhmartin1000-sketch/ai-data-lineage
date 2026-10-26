package com.example.lineage.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class LineageSearchService {

    private static final Pattern PROCEDURE_START = Pattern.compile(
            "^\\s*(?:PROCEDURE|FUNCTION)\\s+([A-Z][A-Z0-9_$#]*)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PROCEDURE_END = Pattern.compile(
            "^\\s*END\\s+([A-Z][A-Z0-9_$#]*)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final Path packagesDirectory;

    @Autowired
    public LineageSearchService(@Value("${lineage.packages-directory:../packages}") String packagesDirectory) {
        this(Path.of(packagesDirectory));
    }

    LineageSearchService(Path packagesDirectory) {
        this.packagesDirectory = packagesDirectory.toAbsolutePath().normalize();
    }

    public List<LineageSearchResult> search(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }

        Pattern columnPattern = Pattern.compile(
                "(?i)(?<![A-Z0-9_$#])" + Pattern.quote(column.trim()) + "(?![A-Z0-9_$#])"
        );
        List<LineageSearchResult> results = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(packagesDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isPackageSource)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> searchFile(path, columnPattern, results));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read package sources from " + packagesDirectory, exception);
        }

        return List.copyOf(results);
    }

    private boolean isPackageSource(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".pks") || fileName.endsWith(".pkb");
    }

    private void searchFile(Path path, Pattern columnPattern, List<LineageSearchResult> results) {
        boolean packageBody = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pkb");
        String currentProcedure = null;
        boolean inBlockComment = false;

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String originalLine = lines.get(index);
                CommentResult commentResult = removeComments(originalLine, inBlockComment);
                inBlockComment = commentResult.inBlockComment();
                String searchableLine = commentResult.code();

                Matcher procedureStart = PROCEDURE_START.matcher(searchableLine);
                String declaredProcedure = procedureStart.find() ? procedureStart.group(1) : null;
                String resultProcedure = packageBody ? currentProcedure : declaredProcedure;
                if (!packageBody && declaredProcedure != null) {
                    resultProcedure = declaredProcedure;
                }

                if (columnPattern.matcher(searchableLine).find()) {
                    results.add(new LineageSearchResult(
                            path.getFileName().toString(),
                            index + 1,
                            originalLine.strip(),
                            resultProcedure
                    ));
                }

                if (packageBody && declaredProcedure != null) {
                    currentProcedure = declaredProcedure;
                }
                if (packageBody) {
                    Matcher procedureEnd = PROCEDURE_END.matcher(searchableLine);
                    if (procedureEnd.matches() && procedureEnd.group(1).equalsIgnoreCase(currentProcedure)) {
                        currentProcedure = null;
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read package source " + path, exception);
        }
    }

    private CommentResult removeComments(String line, boolean inBlockComment) {
        StringBuilder code = new StringBuilder();
        boolean inSingleQuotedText = false;
        boolean inDoubleQuotedIdentifier = false;

        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }

            if (!inSingleQuotedText && !inDoubleQuotedIdentifier && current == '-' && next == '-') {
                break;
            }
            if (!inSingleQuotedText && !inDoubleQuotedIdentifier && current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }

            code.append(current);
            if (current == '\'' && !inDoubleQuotedIdentifier) {
                if (inSingleQuotedText && next == '\'') {
                    code.append(next);
                    index++;
                } else {
                    inSingleQuotedText = !inSingleQuotedText;
                }
            } else if (current == '"' && !inSingleQuotedText) {
                inDoubleQuotedIdentifier = !inDoubleQuotedIdentifier;
            }
        }

        return new CommentResult(code.toString(), inBlockComment);
    }

    private record CommentResult(String code, boolean inBlockComment) {
    }
}
