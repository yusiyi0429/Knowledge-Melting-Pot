package com.knowledgemeltingpot.workbench.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.EmbeddingProviderException;
import com.knowledgemeltingpot.workbench.application.port.ChunkEmbeddingRepository;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingPort;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.application.port.IngestCheckpointRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.MaterialBlobRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.port.VirusScanPort;
import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.IngestStage;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialBlob;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialIngestAttempt;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SecurityPartition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "workbench.material-ingest", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "workbench.object-storage", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "workbench.content.clamav", name = "enabled", havingValue = "true")
public class IngestMaterialJobHandler implements JobHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestMaterialJobHandler.class);
    private static final long MAX_DOWNLOAD_BYTES = Material.MAX_UPLOAD_BYTES;
    private static final int DOWNLOAD_BUFFER = 8192;

    private final ObjectStoragePort objectStorage;
    private final VirusScanPort virusScan;
    private final MaterialParserPort parser;
    private final MaterialRepository materialRepository;
    private final MaterialBlobRepository blobRepository;
    private final IngestCheckpointRepository checkpointRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingProfileVersionRepository embeddingProfileRepository;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final List<EmbeddingPort> embeddingPorts;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IngestMaterialJobHandler(ObjectStoragePort objectStorage, VirusScanPort virusScan,
            MaterialParserPort parser, MaterialRepository materialRepository, MaterialBlobRepository blobRepository,
            IngestCheckpointRepository checkpointRepository, ChunkRepository chunkRepository,
            EmbeddingProfileVersionRepository embeddingProfileRepository,
            ChunkEmbeddingRepository chunkEmbeddingRepository, List<EmbeddingPort> embeddingPorts,
            ObjectMapper objectMapper, Clock clock) {
        this.objectStorage = objectStorage;
        this.virusScan = virusScan;
        this.parser = parser;
        this.materialRepository = materialRepository;
        this.blobRepository = blobRepository;
        this.checkpointRepository = checkpointRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingProfileRepository = embeddingProfileRepository;
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.embeddingPorts = List.copyOf(embeddingPorts);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.INGEST;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        UUID jobId = leasedJob.job().id();
        Payload payload;
        try {
            payload = objectMapper.readValue(leasedJob.job().payloadJson(), Payload.class);
        } catch (IOException exception) {
            return fail(jobId, null, IngestStage.STARTED, "INGEST_PAYLOAD_INVALID", false, exception);
        }
        UUID materialId = leasedJob.job().aggregateId();
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {
            return fail(jobId, null, IngestStage.STARTED, "MATERIAL_NOT_FOUND", false, null);
        }
        int attempt = leasedJob.attempt();
        Instant now = Instant.now(clock);
        Optional<MaterialIngestAttempt> prior = checkpointRepository.findByJobId(jobId);
        if (prior.isEmpty()) {
            checkpointRepository.startAttempt(new MaterialIngestAttempt(jobId, material.id(), attempt,
                    IngestStage.STARTED, null, false, now, null, null, null, null, null));
        } else if (prior.get().stage() == IngestStage.FAILED) {
            // Terminal failure: this job was manually retried (or a failed attempt
            // was re-claimed). Only a retryable failure may bring the material back
            // to UPLOADED and re-run; non-retryable failures are rejected stably.
            if (!prior.get().retryable()) {
                return fail(jobId, material.id(), IngestStage.STARTED, "RETRY_NOT_ALLOWED", false, null);
            }
            materialRepository.transitionStatus(materialId, MaterialStatus.FAILED, MaterialStatus.UPLOADED, now);
            checkpointRepository.reopenAttempt(jobId, attempt, now);
        } else {
            // Unfinished attempt from a lease re-claim: synchronize the attempt
            // number with the job and reset this attempt to a clean STARTED state.
            checkpointRepository.reopenAttempt(jobId, attempt, now);
        }
        context.progress(5, "HEAD_VERIFIED");

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("kmp-ingest-", ".tmp");
            ObjectStoragePort.ObjectHead head = objectStorage.head(ObjectStoragePort.StorageZone.QUARANTINE,
                    material.objectKey());
            if (head.sizeBytes() != payload.expectedSizeBytes()) {
                return fail(jobId, material.id(), IngestStage.HEAD_VERIFIED, "SIZE_MISMATCH", false, null);
            }
            checkpointRepository.updateStage(jobId, IngestStage.HEAD_VERIFIED);
            context.progress(10, "HASH_VERIFIED");

            downloadBounded(head, tempFile, context);
            String actualSha256 = sha256(tempFile);
            if (!actualSha256.equalsIgnoreCase(payload.expectedSha256())) {
                return fail(jobId, material.id(), IngestStage.HASH_VERIFIED, "SHA256_MISMATCH", false, null);
            }
            checkpointRepository.updateStage(jobId, IngestStage.HASH_VERIFIED);
            context.progress(20, "MIME_VERIFIED");

            String detectedMime = parser.detectMediaType(tempFile);
            if (!compatible(material.mediaType(), detectedMime, material.format())) {
                return fail(jobId, material.id(), IngestStage.MIME_VERIFIED, "MIME_MISMATCH", false, null);
            }
            checkpointRepository.updateStage(jobId, IngestStage.MIME_VERIFIED);
            context.progress(30, "MALWARE_CLEAN");

            VirusScanPort.ScanReport scan = virusScan.scan(tempFile);
            if (!scan.clean()) {
                return fail(jobId, material.id(), IngestStage.MALWARE_CLEAN, "MALWARE_DETECTED", false, null);
            }
            checkpointRepository.updateStage(jobId, IngestStage.MALWARE_CLEAN);
            context.progress(45, "ARCHIVE_BUDGET_VERIFIED");
            checkpointRepository.updateStage(jobId, IngestStage.ARCHIVE_BUDGET_VERIFIED);

            SecurityPartition partition = securityPartition(material.id());
            MaterialBlob blob = blobRepository.findByPartitionAndSha256(partition, actualSha256).orElse(null);
            String parserName;
            String parserVersion;
            List<MaterialChunk> chunks;
            if (blob != null && chunkRepository.existsForBlob(blob.id())) {
                // A previous run already committed chunks for this verified blob:
                // reuse them instead of parsing the same content again.
                parserName = blob.parserName();
                parserVersion = blob.parserVersion();
                chunks = chunkRepository.findByBlob(blob.id());
                checkpointRepository.updateStage(jobId, IngestStage.CHUNKS_COMMITTED);
                context.progress(80, "CHUNKS_COMMITTED");
            } else {
                context.progress(55, "PARSED");
                MaterialParserPort.MaterialParseResult parseResult = parser.parse(tempFile, material.format());
                if (parseResult instanceof MaterialParserPort.MaterialParseResult.OcrRequired ocr) {
                    parserName = ocr.parserName();
                    parserVersion = ocr.parserVersion();
                    return fail(jobId, material.id(), IngestStage.PARSED, "OCR_REQUIRED", false, null);
                }
                MaterialParserPort.MaterialParseResult.Parsed parsed =
                        (MaterialParserPort.MaterialParseResult.Parsed) parseResult;
                parserName = parsed.parserName();
                parserVersion = parsed.parserVersion();
                checkpointRepository.updateStage(jobId, IngestStage.PARSED);
                if (blob == null) {
                    String cleanObjectKey = cleanObjectKey(partition, actualSha256, material.format());
                    objectStorage.copyToVerified(ObjectStoragePort.StorageZone.QUARANTINE, material.objectKey(),
                            targetZone(partition), cleanObjectKey);
                    ObjectStoragePort.ObjectHead verifiedHead = objectStorage.head(targetZone(partition),
                            cleanObjectKey);
                    if (verifiedHead.sizeBytes() != payload.expectedSizeBytes()) {
                        return fail(jobId, material.id(), IngestStage.OBJECT_VERIFIED,
                                "VERIFIED_SIZE_MISMATCH", false, null);
                    }
                    try (InputStream verified = objectStorage.open(targetZone(partition), cleanObjectKey)) {
                        if (!actualSha256.equals(sha256Bounded(verified))) {
                            return fail(jobId, material.id(), IngestStage.OBJECT_VERIFIED,
                                    "VERIFIED_SHA256_MISMATCH", false, null);
                        }
                    }
                    blob = blobRepository.insert(new MaterialBlob(UUID.randomUUID(), partition, actualSha256,
                            cleanObjectKey, verifiedHead.sizeBytes(), detectedMime, scan.engineVersion(),
                            scan.signatureVersion(), parserName, parserVersion, now));
                }
                List<MaterialChunk> newChunks = toChunks(blob.id(), parserVersion, parsed.segments(), now);
                chunkRepository.commitAll(blob.id(), parserVersion, newChunks);
                chunks = chunkRepository.findByBlob(blob.id());
                checkpointRepository.updateStage(jobId, IngestStage.CHUNKS_COMMITTED);
                context.progress(80, "CHUNKS_COMMITTED");
            }

            commitEmbeddings(jobId, chunks, context);

            context.progress(90, "OBJECT_VERIFYING");
            if (material.status() == MaterialStatus.READY) {
                // A prior run committed the blob and chunks but lost its lease
                // before succeeding; replay idempotently.
                Instant replayAt = Instant.now(clock);
                checkpointRepository.completeAttempt(jobId, IngestStage.OBJECT_VERIFIED, parserName, parserVersion,
                        replayAt);
                return JobHandlingResult.success(blob.id().toString());
            }
            if (!materialRepository.updateBlobId(material.id(), blob.id(), MaterialStatus.UPLOADED, MaterialStatus.READY,
                    now)) {
                return fail(jobId, material.id(), IngestStage.OBJECT_VERIFIED, "MATERIAL_STATE_RACE", true, null);
            }
            checkpointRepository.completeAttempt(jobId, IngestStage.OBJECT_VERIFIED, parserName, parserVersion,
                    now);
            return JobHandlingResult.success(blob.id().toString());
        } catch (EmbeddingProviderException exception) {
            LOGGER.warn("Ingest job {} embedding Provider failed: {}", jobId, exception.code());
            return fail(jobId, material.id(), IngestStage.FAILED, exception.code(), exception.retryable(), null);
        } catch (Exception exception) {
            LOGGER.error("Ingest job {} failed: {}", jobId, exception.getClass().getSimpleName());
            return fail(jobId, material.id(), IngestStage.FAILED, "INGEST_EXCEPTION", true, exception);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
        }
    }

    /**
     * Embeds chunks only when a real profile and provider exist; otherwise a
     * stable diagnostic is recorded and the material may still reach READY with
     * its committed chunks. Vectors are never fabricated.
     */
    private void commitEmbeddings(UUID jobId, List<MaterialChunk> chunks, WorkerJobContext context) {
        Optional<EmbeddingProfileVersion> profile = embeddingProfileRepository.findActive();
        Optional<EmbeddingPort> provider = profile.flatMap(active -> embeddingPorts.stream()
                .filter(port -> active.provider().equals(port.provider()))
                .findFirst());
        if (profile.isEmpty() || provider.isEmpty()) {
            context.diagnostic(80, "CHUNKS_COMMITTED", "EMBEDDING_PROVIDER_UNCONFIGURED");
            return;
        }
        EmbeddingProfileVersion active = profile.orElseThrow();
        Set<UUID> presentIds = chunkEmbeddingRepository.findPresentChunkIds(active.id(),
                chunks.stream().map(MaterialChunk::id).toList());
        List<MaterialChunk> missing = chunks.stream()
                .filter(chunk -> !presentIds.contains(chunk.id()))
                .toList();
        for (int start = 0; start < missing.size(); start += 10) {
            List<MaterialChunk> batch = missing.subList(start, Math.min(start + 10, missing.size()));
            List<ChunkEmbedding> embeddings = provider.orElseThrow().embed(batch, active);
            validateEmbeddings(batch, active, embeddings);
            int batchPresent = chunkEmbeddingRepository.writeAll(embeddings);
            if (batchPresent < batch.size()) {
                throw new IllegalStateException("embedding batch write incomplete: " + batchPresent + "/"
                        + batch.size() + " present");
            }
            int completed = Math.min(chunks.size(), presentIds.size() + start + batch.size());
            int percent = 80 + Math.min(5, (completed * 5) / Math.max(chunks.size(), 1));
            context.progress(percent, "EMBEDDINGS_WRITING");
        }
        int present = presentIds.size() + missing.size();
        if (present < chunks.size()) {
            throw new IllegalStateException("embedding write incomplete: " + present + "/" + chunks.size()
                    + " present");
        }
        checkpointRepository.updateStage(jobId, IngestStage.EMBEDDINGS_COMMITTED);
        context.progress(86, "EMBEDDINGS_COMMITTED");
    }

    private void validateEmbeddings(List<MaterialChunk> chunks, EmbeddingProfileVersion profile,
            List<ChunkEmbedding> embeddings) {
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalStateException("embedding provider returned an incomplete chunk set");
        }
        Map<UUID, MaterialChunk> expected = chunks.stream()
                .collect(Collectors.toUnmodifiableMap(MaterialChunk::id, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (ChunkEmbedding embedding : embeddings) {
            MaterialChunk chunk = expected.get(embedding.chunkId());
            if (chunk == null || !seen.add(embedding.chunkId())
                    || !profile.id().equals(embedding.profileVersionId())
                    || profile.dimension() != embedding.dimension()
                    || !chunk.contentHash().equals(embedding.contentHash())) {
                throw new IllegalStateException("embedding provider returned an invalid chunk projection");
            }
        }
    }

    private List<MaterialChunk> toChunks(UUID blobId, String parserVersion,
            List<MaterialParserPort.ParsedSegment> segments, Instant now) {
        List<MaterialChunk> chunks = new ArrayList<>(segments.size());
        for (MaterialParserPort.ParsedSegment segment : segments) {
            chunks.add(MaterialChunk.fromParsed(UUID.randomUUID(), blobId, segment.ordinal(),
                    sourceRefCode(blobId, segment.ordinal()), segment.locator(), segment.text(), parserVersion, now));
        }
        return chunks;
    }

    private String sourceRefCode(UUID blobId, int ordinal) {
        String compact = blobId.toString().replace("-", "");
        return "SRC-" + compact + "-" + ordinal;
    }

    private void downloadBounded(ObjectStoragePort.ObjectHead head, Path target, WorkerJobContext context)
            throws IOException {
        if (head.sizeBytes() > MAX_DOWNLOAD_BYTES) {
            throw new IllegalStateException("object exceeds download budget");
        }
        long total = 0;
        try (InputStream input = objectStorage.open(ObjectStoragePort.StorageZone.QUARANTINE, head.objectKey());
                var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IllegalStateException("download exceeded budget");
                }
                output.write(buffer, 0, read);
                if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
                    throw new IOException("download interrupted");
                }
            }
        }
    }

    private String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
                DigestInputStream digest = new DigestInputStream(input,
                        MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[8192];
            while (digest.read(buffer) != -1) {
                // drain
            }
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String sha256Bounded(InputStream input) throws IOException {
        try (DigestInputStream digest = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER];
            long total = 0;
            int read;
            while ((read = digest.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("verified object exceeded budget");
                }
            }
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private boolean compatible(String declared, String detected, MaterialFormat format) {
        if (detected == null) {
            return false;
        }
        String expected = format.mediaType();
        return detected.equalsIgnoreCase(expected) || detected.equalsIgnoreCase(declared);
    }

    private SecurityPartition securityPartition(UUID materialId) {
        List<RoundMaterial> bindings = materialRepository.findBindings(materialId);
        boolean holdout = bindings.stream().anyMatch(b -> b.partition() == MaterialPartition.LABELED_HOLDOUT);
        return holdout ? SecurityPartition.HOLDOUT : SecurityPartition.KNOWLEDGE;
    }

    private ObjectStoragePort.StorageZone targetZone(SecurityPartition partition) {
        return partition == SecurityPartition.HOLDOUT
                ? ObjectStoragePort.StorageZone.VERIFIED_HOLDOUT
                : ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE;
    }

    private String cleanObjectKey(SecurityPartition partition, String sha256, MaterialFormat format) {
        return partition.name().toLowerCase() + "/" + sha256.substring(0, 2) + "/"
                + sha256.substring(2, 4) + "/" + sha256 + "." + format.extension();
    }

    private JobHandlingResult fail(UUID jobId, UUID materialId, IngestStage stage, String code, boolean retryable,
            Exception exception) {
        String message = exception == null ? code : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        checkpointRepository.failAttempt(jobId, stage, code, retryable, Instant.now(clock));
        if (materialId != null) {
            materialRepository.transitionStatus(materialId, MaterialStatus.UPLOADED, MaterialStatus.FAILED,
                    Instant.now(clock));
        }
        return JobHandlingResult.failure(code, message);
    }

    public record Payload(UUID intentId, String objectKey, String clientEtag, String expectedSha256,
            long expectedSizeBytes, MaterialFormat format) {
    }
}
