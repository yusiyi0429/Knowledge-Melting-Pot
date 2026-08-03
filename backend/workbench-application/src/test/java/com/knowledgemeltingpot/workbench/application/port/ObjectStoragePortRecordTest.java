package com.knowledgemeltingpot.workbench.application.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObjectStoragePortRecordTest {

    @Test
    void uploadedPartRequiresPositivePartNumber() {
        assertThatThrownBy(() -> new ObjectStoragePort.UploadedPart(0, "etag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partNumber");
    }

    @Test
    void uploadedPartRequiresNonBlankEtag() {
        assertThatThrownBy(() -> new ObjectStoragePort.UploadedPart(1, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("etag");
    }

    @Test
    void objectHeadRequiresNonNegativeSize() throws MalformedURLException {
        assertThatThrownBy(() -> new ObjectStoragePort.ObjectHead("key", -1, "etag", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
    }

    @Test
    void presignedPartRequiresPositivePartNumber() throws MalformedURLException {
        URL url = new URL("https://example.com/upload");
        assertThatThrownBy(() -> new ObjectStoragePort.PresignedPart(0, url, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partNumber");
    }
}
