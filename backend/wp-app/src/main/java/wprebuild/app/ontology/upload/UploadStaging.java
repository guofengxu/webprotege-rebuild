package wprebuild.app.ontology.upload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.UploadProperties;
import wprebuild.app.shared.ConflictException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Local-disk staging for upload-and-merge previews (BS-14 / P6-27).
 *
 * <p>Files live under {@code wp.upload.staging-dir} and are named by {@code uploadId} only (never the
 * original upload name). Ownership is checked on every read. Expired entries are removed by a
 * background sweep and also on {@link #find}. {@link StagedUploadMeta#createdAt()} from the caller is
 * ignored; staging always stamps {@code createdAt} from the injected {@link Clock}.
 *
 * <p><b>Single instance only.</b> Staging is process-local memory plus the local filesystem. Sharing
 * across instances is deferred to P6-40.
 */
@Component
public final class UploadStaging implements DisposableBean {

    public static final String TOO_MANY_STAGED_UPLOADS = "TOO_MANY_STAGED_UPLOADS";

    private static final int MAX_PER_USER = 3;

    private static final Set<PosixFilePermission> DIR_PERMS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> FILE_PERMS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Logger log = LoggerFactory.getLogger(UploadStaging.class);

    private final Path stagingDir;

    private final Duration ttl;

    private final Clock clock;

    private final ConcurrentHashMap<UUID, StagedUpload> entries = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UserId, Object> userLocks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    private final ScheduledFuture<?> periodic;

    @Autowired
    public UploadStaging(UploadProperties properties, Clock clock) {
        this(properties, clock, true);
    }

    /** Package-visible so unit tests can disable the background sweeper. */
    UploadStaging(UploadProperties properties, Clock clock, boolean startSweeper) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagingDir = properties.stagingDir().toAbsolutePath().normalize();
        this.ttl = properties.stagingTtl();
        try {
            Files.createDirectories(this.stagingDir);
            restrictPermissions(this.stagingDir, DIR_PERMS);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot create upload staging directory " + this.stagingDir, ex);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("wp-upload-staging").factory());
        if (startSweeper) {
            long periodMillis = Math.max(ttl.toMillis() / 2, TimeUnit.MINUTES.toMillis(1));
            this.periodic = scheduler.scheduleWithFixedDelay(
                    this::safePurge, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        } else {
            this.periodic = null;
        }
    }

    /**
     * Moves {@code source} into the staging directory under a fresh {@code uploadId}. The source
     * path is removed when the move succeeds. Per-user staging is serialized so the three-upload
     * cap cannot race.
     */
    public UUID stage(ProjectId projectId, UserId userId, Path source, StagedUploadMeta meta) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(meta, "meta");
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            purgeExpired();
            long owned = entries.values().stream()
                    .filter(entry -> entry.userId().equals(userId))
                    .filter(entry -> !expired(entry))
                    .count();
            if (owned >= MAX_PER_USER) {
                throw new ConflictException(
                        TOO_MANY_STAGED_UPLOADS, "user already has the maximum number of staged uploads");
            }
            UUID uploadId = UUID.randomUUID();
            Path target = stagingDir.resolve(uploadId.toString());
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                restrictPermissions(target, FILE_PERMS);
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot stage upload " + uploadId, ex);
            }
            StagedUploadMeta stamped =
                    new StagedUploadMeta(meta.mode(), meta.targetOntology(), meta.baseRevision(), clock.instant());
            StagedUpload staged = new StagedUpload(uploadId, projectId, userId, target, stamped);
            entries.put(uploadId, staged);
            return uploadId;
        }
    }

    /**
     * Returns the staged upload when it exists, belongs to {@code projectId}/{@code userId}, and has
     * not expired. Expired or foreign entries yield empty (and expired files are deleted). Callers
     * must treat a missing file as gone if a concurrent purge deletes it after this returns.
     */
    public Optional<StagedUpload> find(ProjectId projectId, UserId userId, UUID uploadId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(uploadId, "upload id");
        StagedUpload staged = entries.get(uploadId);
        if (staged == null) {
            return Optional.empty();
        }
        if (expired(staged)) {
            remove(uploadId, staged);
            return Optional.empty();
        }
        if (!staged.projectId().equals(projectId) || !staged.userId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.of(staged);
    }

    /** Deletes the staged file and registration when the caller owns it. Missing entries are ignored. */
    public void discard(ProjectId projectId, UserId userId, UUID uploadId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(uploadId, "upload id");
        StagedUpload staged = entries.get(uploadId);
        if (staged == null) {
            return;
        }
        if (!staged.projectId().equals(projectId) || !staged.userId().equals(userId)) {
            return;
        }
        remove(uploadId, staged);
    }

    /** Removes every expired registration and its file. Safe to call from tests. */
    public void purgeExpired() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, StagedUpload> entry : entries.entrySet()) {
            if (expired(entry.getValue(), now)) {
                remove(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void destroy() {
        if (periodic != null) {
            periodic.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void safePurge() {
        try {
            purgeExpired();
        } catch (RuntimeException ex) {
            log.warn("upload staging purge failed", ex);
        }
    }

    private boolean expired(StagedUpload staged) {
        return expired(staged, clock.instant());
    }

    private boolean expired(StagedUpload staged, Instant now) {
        return !staged.meta().createdAt().plus(ttl).isAfter(now);
    }

    private void remove(UUID uploadId, StagedUpload staged) {
        entries.remove(uploadId, staged);
        try {
            Files.deleteIfExists(staged.file());
        } catch (IOException ex) {
            log.warn("could not delete staged upload {}", uploadId, ex);
        }
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems (e.g. some CI temp mounts) skip owner-only mode.
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot set permissions on " + path, ex);
        }
    }
}
