package com.knowledgemeltingpot.workbench.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.application.port.VirusScanException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClamAvVirusScanPortTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanFileReturnsCleanReport() throws Exception {
        try (ClamAvFakeServer server = new ClamAvFakeServer(ClamAvFakeServer.Mode.CLEAN)) {
            ClamAvVirusScanPort port = port(server.port());
            Path file = tempDir.resolve("clean.txt");
            Files.writeString(file, "hello world");

            var report = port.scan(file);

            assertThat(report.clean()).isTrue();
            assertThat(report.engineVersion()).contains("ClamAV");
            assertThat(report.signatureVersion()).isEqualTo("12345");
        }
    }

    @Test
    void infectedFileReturnsInfectedReport() throws Exception {
        try (ClamAvFakeServer server = new ClamAvFakeServer(ClamAvFakeServer.Mode.INFECTED)) {
            ClamAvVirusScanPort port = port(server.port());
            Path file = tempDir.resolve("eicar.txt");
            Files.writeString(file, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");

            var report = port.scan(file);

            assertThat(report.clean()).isFalse();
        }
    }

    @Test
    void silentServerFailsClosedWithTimeout() throws Exception {
        try (ClamAvFakeServer server = new ClamAvFakeServer(ClamAvFakeServer.Mode.SILENT)) {
            ClamAvVirusScanPort port = new ClamAvVirusScanPort(new ClamAvProperties(true, "127.0.0.1",
                    server.port(), Duration.ofMillis(100), Duration.ofMillis(100), 4096));
            Path file = tempDir.resolve("ignored.txt");
            Files.writeString(file, "ignored");

            assertThatThrownBy(() -> port.scan(file))
                    .isInstanceOf(VirusScanException.class)
                    .hasMessageContaining("SCAN_TIMEOUT");
        }
    }

    private ClamAvVirusScanPort port(int port) {
        return new ClamAvVirusScanPort(new ClamAvProperties(true, "127.0.0.1", port,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096));
    }
}
