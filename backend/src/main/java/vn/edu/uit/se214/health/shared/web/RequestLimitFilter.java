package vn.edu.uit.se214.health.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.edu.uit.se214.health.shared.AppProperties;

/**
 * Per-client rate limit (fixed one-minute window, in memory) and request body size limit.
 * Enough for one instance; a shared store is needed if the backend ever runs on several nodes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLimitFilter extends OncePerRequestFilter {
  private final ErrorWriter errors;
  private final long maxBodyBytes;
  private final int limitPerMinute;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public RequestLimitFilter(ErrorWriter errors, AppProperties properties) {
    this.errors = errors;
    this.maxBodyBytes = properties.maxBodyBytes();
    this.limitPerMinute = properties.rateLimitPerMinute();
  }

  private record Window(long minute, AtomicInteger count) {}

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!allow(request.getRemoteAddr())) {
      errors.write(request, response, 429, "RATE_LIMITED", "Quá nhiều yêu cầu. Vui lòng thử lại sau.");
      return;
    }
    if (request.getContentLengthLong() > maxBodyBytes) {
      errors.write(request, response, 413, "PAYLOAD_TOO_LARGE", "Nội dung vượt quá giới hạn cho phép.");
      return;
    }
    chain.doFilter(new LimitedBodyRequest(request, maxBodyBytes), response);
  }

  private boolean allow(String client) {
    long minute = System.currentTimeMillis() / 60_000;
    if (windows.size() > 10_000) windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
    Window window =
        windows.compute(
            client,
            (key, current) ->
                current == null || current.minute() != minute
                    ? new Window(minute, new AtomicInteger())
                    : current);
    return window.count().incrementAndGet() <= limitPerMinute;
  }

  /** Thrown when a body without Content-Length grows past the limit. */
  public static class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException() {
      super("PAYLOAD_TOO_LARGE");
    }
  }

  private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
    private final long limit;

    LimitedBodyRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream in = super.getInputStream();
      return new ServletInputStream() {
        private long read;

        private void count(long n) throws PayloadTooLargeException {
          read += n;
          if (read > limit) throw new PayloadTooLargeException();
        }

        @Override
        public int read() throws IOException {
          int b = in.read();
          if (b >= 0) count(1);
          return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          int n = in.read(buffer, offset, length);
          if (n > 0) count(n);
          return n;
        }

        @Override
        public boolean isFinished() {
          return in.isFinished();
        }

        @Override
        public boolean isReady() {
          return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          in.setReadListener(listener);
        }
      };
    }
  }
}
