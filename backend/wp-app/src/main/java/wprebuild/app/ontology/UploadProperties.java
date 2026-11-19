package wprebuild.app.ontology;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Upload limits and staging directory.
 *
 * <p>{@code wp.upload.max-size} caps the multipart body and a zip expanded on disk (default 200MB).
 * {@code wp.upload.staging-dir} holds merge-upload staging files (default {@code java.io.tmpdir}/wp-staging).
 * {@code wp.upload.staging-ttl} is how long a staged file is kept (default 30 minutes).
 */
@ConfigurationProperties(prefix = "wp.upload")
public record UploadProperties(
        @DefaultValue("200MB") DataSize maxSize,
        Path stagingDir,
        @DefaultValue("30m") Duration stagingTtl) {

    public UploadProperties {
        if (maxSize == null || maxSize.toBytes() < 1) {
            throw new IllegalArgumentException("wp.upload.max-size must be positive");
        }
        if (stagingDir == null) {
            stagingDir = Path.of(System.getProperty("java.io.tmpdir"), "wp-staging");
        }
        if (stagingTtl == null || stagingTtl.isZero() || stagingTtl.isNegative()) {
            throw new IllegalArgumentException("wp.upload.staging-ttl must be positive");
        }
    }
}
