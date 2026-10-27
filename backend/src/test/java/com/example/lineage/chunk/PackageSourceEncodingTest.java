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

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);

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

        PlsqlChunkingService service = new PlsqlChunkingService(directory, 120);

        assertThat(service.loadChunks()).isEmpty();
        assertThat(service.statistics().readFiles()).isZero();
        assertThat(service.statistics().failedFiles()).isEqualTo(1);
        assertThat(service.statistics().errorSummary()).anySatisfy(message ->
                assertThat(message).contains("invalid-bom.pck", "MalformedInputException"));
    }
}
