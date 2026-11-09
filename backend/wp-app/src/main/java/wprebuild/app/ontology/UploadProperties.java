package wprebuild.app.ontology;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** {@code wp.upload.max-size} caps the multipart body and a zip expanded on disk. Default is 200MB. */
@ConfigurationProperties(prefix = "wp.upload")
public record UploadProperties(@DefaultValue("200MB") DataSize maxSize) {

    public UploadProperties {
        if (maxSize == null || maxSize.toBytes() < 1) {
            throw new IllegalArgumentException("wp.upload.max-size must be positive");
        }
    }
}
