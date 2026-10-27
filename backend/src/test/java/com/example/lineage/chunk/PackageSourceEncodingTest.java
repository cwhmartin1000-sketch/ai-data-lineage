package com.example.lineage.chunk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageSourceEncodingTest {

    @TempDir
    Path directory;

    private static final String SOURCE = "CREATE OR REPLACE PACKAGE BODY PKG_ENCODING AS\r\n"
            + "  -- 保單生效日期與客戶資料\r\n"
            + "  PROCEDURE RUN_IT IS\r\n"
            + "  BEGIN\r\n"
            + "    UPDATE TGT_POLICY SET CUSTOMER_NAME = '繁體中文';\r\n"
            + "  END RUN_IT;\r\n"
            + "END PKG_ENCODING;\r\n/\r\n";

    @Test
    void readsMixedUtf8BomAsciiAndMs950FilesWithoutChangingTextOrLineNumbers() throws IOException {
        Files.writeString(directory.resolve("utf8.pkb"), SOURCE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("bom.pck"), "\ufeff" + SOURCE, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ansi.sql"), SOURCE, Charset.forName("MS950"));
        String ascii = SOURCE.replace("保單生效日期與客戶資料", "policy date")
                .replace("繁體中文", "customer");
        Files.writeString(directory.resolve("ascii.pkb"), ascii, StandardCharsets.US_ASCII);

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);
        List<PlsqlChunk> chunks = service.loadChunks();

        assertThat(service.statistics().readFiles()).isEqualTo(4);
        assertThat(service.statistics().failedFiles()).isZero();
        for (String name : List.of("utf8.pkb", "bom.pck", "ansi.sql", "ascii.pkb")) {
            List<PlsqlChunk> fileChunks = chunks.stream().filter(c -> c.fileName().equals(name)).toList();
            assertThat(fileChunks).extracting(PlsqlChunk::packageName).containsOnly("PKG_ENCODING");
            assertThat(String.join("", fileChunks.stream().map(PlsqlChunk::content).toList()))
                    .isEqualTo(name.equals("ascii.pkb") ? ascii : SOURCE);
            assertThat(fileChunks).anySatisfy(chunk -> {
                assertThat(chunk.procedureName()).isEqualTo("RUN_IT");
                assertThat(chunk.startLine()).isEqualTo(3);
                assertThat(chunk.endLine()).isEqualTo(6);
                assertThat(chunk.content()).contains("UPDATE TGT_POLICY");
            });
        }
        assertThat(new PlsqlChunkingService(directory, 120).loadChunks()).isEqualTo(chunks);
    }

    @Test
    void reportsUndecodableFileAndContinuesLoadingValidFiles() throws IOException {
        Files.write(directory.resolve("invalid.pck"), new byte[]{(byte) 0x81});
        Files.writeString(directory.resolve("valid.pkb"), SOURCE, StandardCharsets.UTF_8);

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120, 20000, 16777216, 268435456, false);

        assertThat(service.statistics().scannedFiles()).isEqualTo(2);
        assertThat(service.statistics().readFiles()).isEqualTo(1);
        assertThat(service.statistics().failedFiles()).isEqualTo(1);
        assertThat(service.statistics().errorSummary()).anySatisfy(message ->
                assertThat(message).contains("invalid.pck", "MalformedInputException"));
        assertThat(service.loadChunks()).extracting(PlsqlChunk::fileName).containsOnly("valid.pkb");
    }

    @Test
    void doesNotTreatMalformedBomMarkedUtf8AsAnsi() throws IOException {
        Files.write(directory.resolve("invalid-bom.pck"),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xa4, (byte) 0xa4});

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120, 20000, 16777216, 268435456, false);

        assertThat(service.loadChunks()).isEmpty();
        assertThat(service.statistics().readFiles()).isZero();
        assertThat(service.statistics().failedFiles()).isEqualTo(1);
        assertThat(service.statistics().errorSummary()).anySatisfy(message ->
                assertThat(message).contains("invalid-bom.pck", "MalformedInputException"));
    }
    @Test
    void recoversMalformedAnsiCommentAndPreservesSqlAndLineNumbers() throws IOException {
        byte[] source = SOURCE.getBytes(Charset.forName("MS950"));
        // Corrupt a comment, immediately before CRLF, without changing SQL bytes.
        int offset = SOURCE.substring(0, SOURCE.indexOf("\r\n", SOURCE.indexOf("--")))
                .getBytes(Charset.forName("MS950")).length;
        byte[] damaged = new byte[source.length + 1];
        System.arraycopy(source, 0, damaged, 0, offset);
        damaged[offset] = (byte) 0x81;
        System.arraycopy(source, offset, damaged, offset + 1, source.length - offset);
        Files.write(directory.resolve("damaged.pck"), damaged);
        Files.writeString(directory.resolve("valid.pkb"), SOURCE, StandardCharsets.UTF_8);

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);

        assertThat(service.statistics().readFiles()).isEqualTo(2);
        assertThat(service.statistics().failedFiles()).isZero();
        assertThat(service.statistics().replacementDecodedFiles()).isEqualTo(1);
        assertThat(service.statistics().warningSummary()).singleElement().asString()
                .contains("damaged.pck", "x-windows-950", "offset=" + offset);
        String reconstructed = String.join("", service.loadChunks().stream()
                .filter(c -> c.fileName().equals("damaged.pck")).map(PlsqlChunk::content).toList());
        assertThat(reconstructed).isEqualTo(SOURCE.substring(0, SOURCE.indexOf("\r\n", SOURCE.indexOf("--")))
                + "\ufffd" + SOURCE.substring(SOURCE.indexOf("\r\n", SOURCE.indexOf("--"))));
        assertThat(service.loadChunks()).anySatisfy(chunk -> {
            assertThat(chunk.fileName()).isEqualTo("damaged.pck");
            assertThat(chunk.procedureName()).isEqualTo("RUN_IT");
            assertThat(chunk.startLine()).isEqualTo(3);
            assertThat(chunk.endLine()).isEqualTo(6);
            assertThat(chunk.content()).contains("'繁體中文'");
        });
        assertThat(Files.readAllBytes(directory.resolve("damaged.pck"))).isEqualTo(damaged);
    }

    @Test
    void readsUtf16BomFilesWithoutReplacement() throws IOException {
        for (var charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            Files.write(directory.resolve(charset.name() + ".pck"), ("\ufeff" + SOURCE).getBytes(charset));
        }
        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);
        assertThat(service.statistics().readFiles()).isEqualTo(2);
        assertThat(service.statistics().failedFiles()).isZero();
        assertThat(service.statistics().replacementDecodedFiles()).isZero();
        for (String file : List.of("UTF-16LE.pck", "UTF-16BE.pck")) {
            assertThat(String.join("", service.loadChunks().stream().filter(c -> c.fileName().equals(file))
                    .map(PlsqlChunk::content).toList())).isEqualTo(SOURCE);
        }
    }

    @Test
    void recoversBomMarkedUtf8UsingUtf8RatherThanAnsi() throws IOException {
        byte[] source = ("\ufeff" + SOURCE).getBytes(StandardCharsets.UTF_8);
        byte[] damaged = java.util.Arrays.copyOf(source, source.length + 1);
        damaged[damaged.length - 1] = (byte) 0xff;
        Files.write(directory.resolve("utf8.pck"), damaged);
        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);
        assertThat(service.statistics().readFiles()).isEqualTo(1);
        assertThat(service.statistics().replacementDecodedFiles()).isEqualTo(1);
        assertThat(service.statistics().warningSummary()).singleElement().asString().contains("UTF-8");
        assertThat(String.join("", service.loadChunks().stream().map(PlsqlChunk::content).toList()))
                .isEqualTo(SOURCE + "\ufffd");
    }

    @Test
    void boundsWarningSummaryAndCountsAllRecoveredFiles() throws IOException {
        byte[] source = (SOURCE + "-- ").getBytes(StandardCharsets.US_ASCII);
        byte[] damaged = java.util.Arrays.copyOf(source, source.length + 1);
        damaged[damaged.length - 1] = (byte) 0x81;
        for (int i = 0; i < 7; i++) Files.write(directory.resolve("file" + i + ".pck"), damaged);
        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);
        assertThat(service.statistics().readFiles()).isEqualTo(7);
        assertThat(service.statistics().replacementDecodedFiles()).isEqualTo(7);
        assertThat(service.statistics().warningSummary()).hasSize(5);
        assertThat(service.statistics().failedFiles()).isZero();
    }

    @Test
    void stillEnforcesConfiguredFileCountLimit() throws IOException {
        for (int i = 0; i < 3; i++) Files.writeString(directory.resolve("file" + i + ".pck"), SOURCE);
        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120, 2, 16777216, 268435456);
        assertThat(service.statistics().scannedFiles()).isEqualTo(3);
        assertThat(service.statistics().readFiles()).isEqualTo(2);
        assertThat(service.statistics().failedFiles()).isEqualTo(1);
        assertThat(service.statistics().errorSummary()).singleElement().asString().contains("maximum file count");
    }

}
