package wprebuild.app.revision;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import wprebuild.kernel.codec.OfnLineChangeCodec;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;

/** Registers the kernel codecs the revision tables store by id (ADR-011). */
@Configuration(proxyBeanMethods = false)
public class KernelCodecConfig {

    @Bean
    OfnLineChangeCodec ofnLineChangeCodec() {
        return new OfnLineChangeCodec();
    }

    @Bean
    OfnZipSnapshotCodec ofnZipSnapshotCodec() {
        return new OfnZipSnapshotCodec();
    }
}
