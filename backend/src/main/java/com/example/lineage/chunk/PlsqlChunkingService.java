package com.example.lineage.chunk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class PlsqlChunkingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlsqlChunkingService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".pck", ".pkb", ".pks", ".sql");
    private static final int ERROR_SUMMARY_LIMIT = 5;
    // Windows Traditional Chinese "ANSI" code page; independent of the host OS locale.
    private static final Charset ANSI_CHARSET = Charset.forName("MS950");

    private static final String UNKNOWN = "UNKNOWN";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?PACKAGE(?:\\s+BODY)?\\s+([A-Z][A-Z0-9_$#]*)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ROUTINE_START = Pattern.compile(
            "^\\s*(?:PROCEDURE|FUNCTION)\\s+([A-Z][A-Z0-9_$#]*)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ROUTINE_END = Pattern.compile(
            "^\\s*END\\s+([A-Z][A-Z0-9_$#]*)\\s*;\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_$#]*|[();]");

    private final Path packagesDirectory;
    private final int maxProcedureLines;
    private final int maximumFiles;
    private final long maximumFileBytes;
    private final long maximumTotalBytes;
    private final boolean replaceInvalidCharacters;
    private volatile ScanSnapshot scanSnapshot;

    @Autowired
    public PlsqlChunkingService(
            @Value("${lineage.packages-directory:}") String packagesDirectory,
            @Value("${lineage.chunking.max-lines:120}") int maxProcedureLines,
            @Value("${lineage.packages-scan.maximum-files:20000}") int maximumFiles,
            @Value("${lineage.packages-scan.maximum-file-bytes:16777216}") long maximumFileBytes,
            @Value("${lineage.packages-scan.maximum-total-bytes:268435456}") long maximumTotalBytes,
            @Value("${lineage.packages-scan.replace-invalid-characters:true}") boolean replaceInvalidCharacters
    ) {
        this(resolvePackagesDirectory(packagesDirectory), maxProcedureLines, maximumFiles, maximumFileBytes,
                maximumTotalBytes, replaceInvalidCharacters);
    }

    public PlsqlChunkingService(String packagesDirectory, int maxProcedureLines) {
        this(resolvePackagesDirectory(packagesDirectory), maxProcedureLines);
    }

    PlsqlChunkingService(Path packagesDirectory, int maxProcedureLines) {
        this(packagesDirectory, maxProcedureLines, 20000, 16L * 1024 * 1024, 256L * 1024 * 1024);
    }

    PlsqlChunkingService(Path packagesDirectory, int maxProcedureLines, int maximumFiles,
                         long maximumFileBytes, long maximumTotalBytes) {
        this(packagesDirectory, maxProcedureLines, maximumFiles, maximumFileBytes, maximumTotalBytes, true);
    }

    PlsqlChunkingService(Path packagesDirectory, int maxProcedureLines, int maximumFiles,
                         long maximumFileBytes, long maximumTotalBytes, boolean replaceInvalidCharacters) {
        if (maxProcedureLines < 1) {
            throw new IllegalArgumentException("maxProcedureLines must be at least 1");
        }
        if (maximumFiles < 1 || maximumFileBytes < 1 || maximumTotalBytes < 1) {
            throw new IllegalArgumentException("Package scan limits must be positive");
        }
        this.packagesDirectory = packagesDirectory.toAbsolutePath().normalize();
        this.maxProcedureLines = maxProcedureLines;
        this.maximumFiles = maximumFiles;
        this.maximumFileBytes = maximumFileBytes;
        this.maximumTotalBytes = maximumTotalBytes;
        this.replaceInvalidCharacters = replaceInvalidCharacters;
    }

    private static Path resolvePackagesDirectory(String configuredDirectory) {
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            return Path.of(configuredDirectory.trim());
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> defaults = List.of(workingDirectory.resolve("packages"),
                workingDirectory.resolve("..").resolve("packages").normalize());
        return defaults.stream()
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .findFirst()
                .orElse(workingDirectory.resolve("packages"));
    }

    @PostConstruct
    public void initialize() {
        loadChunks();
        PackageScanStatistics statistics = statistics();
        if (statistics.replacementDecodedFiles() > 0) {
            LOGGER.warn("PL/SQL source decoding used replacement characters: files={}, warnings={}. "
                            + "Affected text is approximate; verify comments, literals and identifiers against original files.",
                    statistics.replacementDecodedFiles(), statistics.warningSummary());
        }
        LOGGER.info("PL/SQL package scan complete: root={}, files={}, read={}, chunks={}, failed={}, elapsedMs={}, errors={}",
                statistics.rootDirectory(), statistics.scannedFiles(), statistics.readFiles(), statistics.chunkCount(),
                statistics.failedFiles(), statistics.elapsedMillis(), statistics.errorSummary());
    }

    public List<ChunkSummary> listChunks() {
        return loadChunks().stream().map(PlsqlChunk::toSummary).toList();
    }

    public Optional<PlsqlChunk> getChunk(String chunkId) {
        return loadChunks().stream().filter(chunk -> chunk.chunkId().equals(chunkId)).findFirst();
    }

    public List<PlsqlChunk> loadChunks() {
        return snapshot().chunks();
    }

    public PackageScanStatistics statistics() {
        return snapshot().statistics();
    }

    private ScanSnapshot snapshot() {
        ScanSnapshot current = scanSnapshot;
        if (current != null) return current;
        synchronized (this) {
            if (scanSnapshot == null) scanSnapshot = scanDirectory();
            return scanSnapshot;
        }
    }

    private ScanSnapshot scanDirectory() {
        long started = System.nanoTime();
        Path root;
        try {
            if (!Files.isDirectory(packagesDirectory, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(packagesDirectory)) {
                throw new IllegalStateException("Package directory does not exist or is not readable: " + packagesDirectory);
            }
            root = packagesDirectory.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to access package directory: " + packagesDirectory, exception);
        }

        List<Path> sourceFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int replacementDecodedFiles = 0;
        int[] failureCount = {0};
        int[] scannedFileCount = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isPackageSource)
                    .filter(path -> path.normalize().startsWith(root))
                    .forEach(path -> {
                        scannedFileCount[0]++;
                        // Keep only a bounded candidate set; source contents are also byte-bounded below.
                        if (sourceFiles.size() < maximumFiles) sourceFiles.add(path);
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan package directory: " + root, exception);
        }
        sourceFiles.sort(Comparator.comparing(path -> relativeName(root, path)));
        if (scannedFileCount[0] > sourceFiles.size()) {
            int skipped = scannedFileCount[0] - sourceFiles.size();
            failureCount[0] += skipped;
            addError(errors, "Additional files skipped: maximum file count reached (" + skipped + ")");
        }

        List<PlsqlChunk> chunks = new ArrayList<>();
        int readFiles = 0;
        long totalBytes = 0;
        for (Path path : sourceFiles) {
            String relative = relativeName(root, path);
            try {
                long fileSize = Files.size(path);
                if (fileSize > maximumFileBytes || totalBytes + fileSize > maximumTotalBytes) {
                    addError(errors, failureCount, relative + " skipped: configured source size limit reached");
                    continue;
                }
                DecodedSource decoded = readPackageSource(path);
                String source = decoded.content();
                if (decoded.replaced()) {
                    replacementDecodedFiles++;
                    addError(warnings, relative + " decoded with " + decoded.encoding()
                            + " replacement characters; first invalid byte offset=" + decoded.firstInvalidByteOffset());
                }
                readFiles++;
                totalBytes += fileSize;
                List<PlsqlChunk> fileChunks = chunkFile(root, path, source);
                if (fileChunks.isEmpty() || fileChunks.stream().anyMatch(chunk -> UNKNOWN.equals(chunk.packageName()))) {
                    addError(errors, failureCount, relative + " parsed with UNKNOWN package/routine metadata");
                }
                chunks.addAll(fileChunks);
            } catch (IOException | RuntimeException exception) {
                addError(errors, failureCount, relative + " failed: " + exception.getClass().getSimpleName());
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        PackageScanStatistics stats = new PackageScanStatistics(root.toString(), scannedFileCount[0], readFiles,
                chunks.size(), failureCount[0], elapsedMillis, List.copyOf(errors), replacementDecodedFiles, List.copyOf(warnings));
        return new ScanSnapshot(List.copyOf(chunks), stats);
    }

    private DecodedSource readPackageSource(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return decodeSource(bytes, 3, StandardCharsets.UTF_8, replaceInvalidCharacters);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return decodeSource(bytes, 2, StandardCharsets.UTF_16LE, replaceInvalidCharacters);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return decodeSource(bytes, 2, StandardCharsets.UTF_16BE, replaceInvalidCharacters);
        }
        try {
            return decodeSource(bytes, 0, StandardCharsets.UTF_8, false);
        } catch (CharacterCodingException invalidUtf8) {
            return decodeSource(bytes, 0, ANSI_CHARSET, replaceInvalidCharacters);
        }
    }

    private DecodedSource decodeSource(byte[] bytes, int offset, Charset charset, boolean allowReplacement)
            throws CharacterCodingException {
        ByteBuffer input = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return new DecodedSource(decoder.decode(input).toString(), charset.name(), false, -1);
        } catch (CharacterCodingException exception) {
            if (!allowReplacement) throw exception;
            int firstInvalidByteOffset = input.position();
            // Recover only in memory. Preserve line endings and make undecodable bytes visible as U+FFFD.
            String content = decoder.reset()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return new DecodedSource(content, charset.name(), true, firstInvalidByteOffset);
        }
    }

    private record DecodedSource(String content, String encoding, boolean replaced, int firstInvalidByteOffset) {
    }

    private void addError(List<String> errors, int[] failureCount, String message) {
        failureCount[0]++;
        addError(errors, message);
    }

    private void addError(List<String> errors, String message) {
        if (errors.size() < ERROR_SUMMARY_LIMIT) errors.add(message);
    }

    private String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private boolean isPackageSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private List<PlsqlChunk> chunkFile(Path root, Path path, String source) {
            List<SourceLine> lines = splitLines(source);
            String packageName = findPackageName(lines).orElse(UNKNOWN);
            String lowerName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean body = lowerName.endsWith(".pkb") || lowerName.endsWith(".pck")
                    || source.matches("(?is).*\\bPACKAGE\\s+BODY\\b.*");
            List<Range> routineRanges = body ? findBodyRoutines(lines) : findSpecRoutines(lines);
            List<PlsqlChunk> chunks = new ArrayList<>();
            int cursor = 0;

            for (Range routine : routineRanges) {
                if (routine.startLineIndex() > cursor) {
                    addChunk(chunks, root, path, packageName, UNKNOWN, source, lines, cursor, routine.startLineIndex());
                }
                for (Range piece : splitLongRoutine(lines, routine)) {
                    addChunk(chunks, root, path, packageName, routine.procedureName(), source, lines,
                            piece.startLineIndex(), piece.endLineIndex());
                }
                cursor = routine.endLineIndex();
            }

            if (cursor < lines.size()) {
                addChunk(chunks, root, path, packageName, UNKNOWN, source, lines, cursor, lines.size());
            }
            return chunks;
    }

    private Optional<String> findPackageName(List<SourceLine> lines) {
        boolean inBlockComment = false;
        for (SourceLine line : lines) {
            CodeLine codeLine = removeComments(line.text(), inBlockComment);
            inBlockComment = codeLine.inBlockComment();
            Matcher matcher = PACKAGE_NAME.matcher(codeLine.code());
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private List<Range> findBodyRoutines(List<SourceLine> lines) {
        List<Range> routines = new ArrayList<>();
        boolean inBlockComment = false;
        int index = 0;
        while (index < lines.size()) {
            CodeLine codeLine = removeComments(lines.get(index).text(), inBlockComment);
            inBlockComment = codeLine.inBlockComment();
            Matcher startMatcher = ROUTINE_START.matcher(codeLine.code());
            if (!startMatcher.find()) {
                index++;
                continue;
            }

            String routineName = startMatcher.group(1);
            int end = findNamedRoutineEnd(lines, index + 1, routineName, inBlockComment);
            if (end < 0) {
                // Keep malformed or unsupported routine text intact and avoid guessing its scope.
                break;
            }
            routines.add(new Range(index, end + 1, routineName));
            index = end + 1;
            inBlockComment = false;
        }
        return routines;
    }

    private int findNamedRoutineEnd(List<SourceLine> lines, int start, String routineName, boolean inBlockComment) {
        for (int index = start; index < lines.size(); index++) {
            CodeLine codeLine = removeComments(lines.get(index).text(), inBlockComment);
            inBlockComment = codeLine.inBlockComment();
            Matcher endMatcher = ROUTINE_END.matcher(codeLine.code());
            if (endMatcher.matches() && endMatcher.group(1).equalsIgnoreCase(routineName)) {
                return index;
            }
        }
        return -1;
    }

    private List<Range> findSpecRoutines(List<SourceLine> lines) {
        List<Range> routines = new ArrayList<>();
        boolean inBlockComment = false;
        int index = 0;
        while (index < lines.size()) {
            CodeLine codeLine = removeComments(lines.get(index).text(), inBlockComment);
            inBlockComment = codeLine.inBlockComment();
            Matcher startMatcher = ROUTINE_START.matcher(codeLine.code());
            if (!startMatcher.find()) {
                index++;
                continue;
            }

            int end = findDeclarationEnd(lines, index, inBlockComment);
            if (end < 0) {
                break;
            }
            routines.add(new Range(index, end + 1, startMatcher.group(1)));
            index = end + 1;
            inBlockComment = false;
        }
        return routines;
    }

    private int findDeclarationEnd(List<SourceLine> lines, int start, boolean inBlockComment) {
        for (int index = start; index < lines.size(); index++) {
            CodeLine codeLine = removeComments(lines.get(index).text(), inBlockComment);
            inBlockComment = codeLine.inBlockComment();
            if (hasDeclarationTerminator(codeLine.code())) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasDeclarationTerminator(String code) {
        boolean inSingleQuote = false;
        int parenthesisDepth = 0;
        for (int index = 0; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '\'') {
                if (inSingleQuote && index + 1 < code.length() && code.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (!inSingleQuote && current == '(') {
                parenthesisDepth++;
            } else if (!inSingleQuote && current == ')') {
                parenthesisDepth--;
            } else if (!inSingleQuote && parenthesisDepth == 0 && current == ';') {
                return true;
            }
        }
        return false;
    }

    private List<Range> splitLongRoutine(List<SourceLine> lines, Range routine) {
        if (routine.endLineIndex() - routine.startLineIndex() <= maxProcedureLines) {
            return List.of(routine);
        }

        List<Integer> safeBoundaries = findSafeSqlBoundaries(lines, routine);
        List<Range> pieces = new ArrayList<>();
        int cursor = routine.startLineIndex();
        while (routine.endLineIndex() - cursor > maxProcedureLines) {
            int limit = cursor + maxProcedureLines;
            int boundary = -1;
            for (int candidate : safeBoundaries) {
                if (candidate > cursor && candidate <= limit && candidate < routine.endLineIndex()) {
                    boundary = candidate;
                }
            }
            if (boundary < 0) {
                boundary = safeBoundaries.stream()
                        .filter(candidate -> candidate > limit && candidate < routine.endLineIndex())
                        .findFirst()
                        .orElse(-1);
            }
            if (boundary < 0) {
                break;
            }
            pieces.add(new Range(cursor, boundary, routine.procedureName()));
            cursor = boundary;
        }
        if (cursor < routine.endLineIndex()) {
            pieces.add(new Range(cursor, routine.endLineIndex(), routine.procedureName()));
        }
        return pieces.isEmpty() ? List.of(routine) : pieces;
    }

    private List<Integer> findSafeSqlBoundaries(List<SourceLine> lines, Range routine) {
        List<Integer> boundaries = new ArrayList<>();
        int parenthesisDepth = 0;
        int caseDepth = 0;
        int ifDepth = 0;
        int loopDepth = 0;
        int nestedBeginDepth = 0;
        boolean mainBeginSeen = false;
        boolean inBlockComment = false;

        for (int index = routine.startLineIndex(); index < routine.endLineIndex(); index++) {
            CodeLine uncommented = removeComments(lines.get(index).text(), inBlockComment);
            inBlockComment = uncommented.inBlockComment();
            String code = maskQuotedText(uncommented.code());
            Matcher tokenMatcher = TOKEN.matcher(code.toUpperCase(Locale.ROOT));
            List<String> tokens = new ArrayList<>();
            while (tokenMatcher.find()) {
                tokens.add(tokenMatcher.group());
            }

            for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
                String token = tokens.get(tokenIndex);
                String previous = tokenIndex > 0 ? tokens.get(tokenIndex - 1) : "";
                String next = tokenIndex + 1 < tokens.size() ? tokens.get(tokenIndex + 1) : "";
                if (token.equals("(")) {
                    parenthesisDepth++;
                } else if (token.equals(")")) {
                    parenthesisDepth = Math.max(0, parenthesisDepth - 1);
                } else if (token.equals("CASE")) {
                    caseDepth++;
                } else if (token.equals("IF") && !previous.equals("END")) {
                    ifDepth++;
                } else if (token.equals("LOOP") && !previous.equals("END")) {
                    loopDepth++;
                } else if (token.equals("BEGIN")) {
                    if (mainBeginSeen) {
                        nestedBeginDepth++;
                    } else {
                        mainBeginSeen = true;
                    }
                } else if (token.equals("END")) {
                    if (next.equals("CASE")) {
                        caseDepth = Math.max(0, caseDepth - 1);
                    } else if (next.equals("IF")) {
                        ifDepth = Math.max(0, ifDepth - 1);
                    } else if (next.equals("LOOP")) {
                        loopDepth = Math.max(0, loopDepth - 1);
                    } else if (caseDepth > 0) {
                        caseDepth--;
                    } else if (nestedBeginDepth > 0) {
                        nestedBeginDepth--;
                    }
                }
            }

            if (mainBeginSeen && parenthesisDepth == 0 && caseDepth == 0 && ifDepth == 0
                    && loopDepth == 0 && nestedBeginDepth == 0 && endsWithStatementTerminator(code)) {
                boundaries.add(index + 1);
            }
        }
        return boundaries;
    }

    private boolean endsWithStatementTerminator(String code) {
        return code.stripTrailing().endsWith(";");
    }

    private String maskQuotedText(String code) {
        StringBuilder result = new StringBuilder(code.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < code.length(); index++) {
            char current = code.charAt(index);
            if (!inDoubleQuote && current == '\'') {
                result.append(' ');
                if (inSingleQuote && index + 1 < code.length() && code.charAt(index + 1) == '\'') {
                    result.append(' ');
                    index++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (!inSingleQuote && current == '"') {
                result.append(' ');
                inDoubleQuote = !inDoubleQuote;
            } else {
                result.append(inSingleQuote || inDoubleQuote ? ' ' : current);
            }
        }
        return result.toString();
    }

    private void addChunk(
            List<PlsqlChunk> chunks,
            Path root,
            Path path,
            String packageName,
            String procedureName,
            String source,
            List<SourceLine> lines,
            int startLineIndex,
            int endLineIndex
    ) {
        if (startLineIndex >= endLineIndex) {
            return;
        }
        int startOffset = lines.get(startLineIndex).startOffset();
        int endOffset = lines.get(endLineIndex - 1).endOffset();
        String content = source.substring(startOffset, endOffset);
        int startLine = startLineIndex + 1;
        int endLine = endLineIndex;
        String fileName = relativeName(root, path);
        chunks.add(new PlsqlChunk(
                createChunkId(fileName, packageName, procedureName,
                        startLine, endLine, content),
                packageName,
                procedureName,
                fileName,
                startLine,
                endLine,
                content
        ));
    }

    private String createChunkId(
            String relativePath,
            String packageName,
            String procedureName,
            int startLine,
            int endLine,
            String content
    ) {
        String stableInput = relativePath.replace('\\', '/') + "\n" + packageName + "\n" + procedureName
                + "\n" + startLine + "\n" + endLine + "\n" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stableInput.getBytes(StandardCharsets.UTF_8));
            return "chunk-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<SourceLine> splitLines(String source) {
        List<SourceLine> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\r' || current == '\n') {
                int lineEnd = index;
                if (current == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    index++;
                }
                lines.add(new SourceLine(source.substring(start, lineEnd), start, index + 1));
                start = index + 1;
            }
        }
        if (start < source.length()) {
            lines.add(new SourceLine(source.substring(start), start, source.length()));
        }
        return lines;
    }

    private CodeLine removeComments(String line, boolean inBlockComment) {
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
        return new CodeLine(code.toString(), inBlockComment);
    }

    private record SourceLine(String text, int startOffset, int endOffset) {
    }

    private record CodeLine(String code, boolean inBlockComment) {
    }

    private record Range(int startLineIndex, int endLineIndex, String procedureName) {
    }

    private record ScanSnapshot(List<PlsqlChunk> chunks, PackageScanStatistics statistics) {
    }
}
