package wprebuild.instances.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request body size on {@code /api/**} (→ 413). Rejects oversized {@code Content-Length} early
 * and wraps the input stream so chunked / missing-length bodies cannot exceed the limit either.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class InstancesRequestSizeFilter extends OncePerRequestFilter {

    private final InstancesProperties properties;

    InstancesRequestSizeFilter(InstancesProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int maxBytes = properties.maxRequestBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "request body exceeds max-request-bytes");
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
        } catch (PayloadTooLargeException ex) {
            if (!response.isCommitted()) {
                response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), ex.getMessage());
            }
        }
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final int maxBytes;
        private ServletInputStream limited;

        LimitedBodyRequest(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitingServletInputStream(super.getInputStream(), maxBytes);
            }
            return limited;
        }
    }

    private static final class LimitingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int maxBytes;
        private int readBytes;

        LimitingServletInputStream(ServletInputStream delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            readBytes += n;
            if (readBytes > maxBytes) {
                throw new PayloadTooLargeException("request body exceeds max-request-bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
