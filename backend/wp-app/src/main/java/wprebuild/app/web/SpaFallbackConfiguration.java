package wprebuild.app.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.HttpResource;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves {@code classpath:/static/} and falls back to {@code index.html} for client routes.
 *
 * <p>{@code /api} stays a 404 ProblemDetail so a missing endpoint is not disguised as the SPA. A missing file with an
 * extension, such as {@code assets/missing.js}, is also a 404. {@code index.html} is revalidated; hashed files under
 * {@code assets/} may be cached for a year.
 */
@Configuration
@ControllerAdvice
public class SpaFallbackConfiguration implements WebMvcConfigurer {

    static final String INDEX = "index.html";

    private static final String API = "api";

    private static final String ASSETS = "assets/";

    private static final CacheControl INDEX_CACHE = CacheControl.noCache();

    private static final CacheControl ASSET_CACHE =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                // A cache here would keep one entry per client route, all pointing at index.html.
                .resourceChain(false)
                .addResolver(new IndexFallbackResolver());
    }

    /** A null resource becomes {@link NoResourceFoundException}; render that as RFC 9457. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> missingStaticResource(NoResourceFoundException exception) {
        ProblemDetail body = exception.getBody();
        if (body.getStatus() == 0) {
            body.setStatus(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.status(body.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    static final class IndexFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            String path = normalize(resourcePath);
            Resource existing = super.getResource(path, location);
            if (existing != null) {
                return cached(path, existing);
            }
            if (isApi(path) || hasExtension(path)) {
                return null;
            }
            Resource index = super.getResource(INDEX, location);
            if (index == null) {
                return null;
            }
            return cached(INDEX, index);
        }

        private static Resource cached(String path, Resource resource) {
            if (INDEX.equals(path)) {
                return new CachedStaticResource(resource, INDEX_CACHE);
            }
            if (path.startsWith(ASSETS)) {
                return new CachedStaticResource(resource, ASSET_CACHE);
            }
            return resource;
        }

        private static String normalize(String resourcePath) {
            String path = resourcePath;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        }

        private static boolean isApi(String path) {
            return API.equals(path) || path.startsWith(API + "/");
        }

        /** The last segment has an extension when a dot is neither first nor last. */
        private static boolean hasExtension(String path) {
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            return dot > 0 && dot < name.length() - 1;
        }
    }

    private static final class CachedStaticResource extends AbstractResource implements HttpResource {

        private final Resource delegate;

        private final HttpHeaders headers;

        private CachedStaticResource(Resource delegate, CacheControl cacheControl) {
            this.delegate = delegate;
            HttpHeaders headers = new HttpHeaders();
            headers.setCacheControl(cacheControl);
            this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
        }

        @Override
        public HttpHeaders getResponseHeaders() {
            return headers;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public boolean exists() {
            return delegate.exists();
        }

        @Override
        public boolean isReadable() {
            return delegate.isReadable();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public long lastModified() throws IOException {
            return delegate.lastModified();
        }

        @Override
        public String getFilename() {
            return delegate.getFilename();
        }

        @Override
        public URL getURL() throws IOException {
            return delegate.getURL();
        }

        @Override
        public URI getURI() throws IOException {
            return delegate.getURI();
        }

        @Override
        public Resource createRelative(String relativePath) throws IOException {
            return delegate.createRelative(relativePath);
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }
    }
}
