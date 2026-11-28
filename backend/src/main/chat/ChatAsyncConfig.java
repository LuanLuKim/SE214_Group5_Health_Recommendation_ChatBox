package chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bounded executor for blocking SSE streaming calls to services/ai (avoids ForkJoinPool.commonPool()). */
@Configuration
class ChatAsyncConfig {
  @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
  ExecutorService chatStreamExecutor() {
    AtomicInteger counter = new AtomicInteger(1);
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "chat-sse-" + counter.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        };
    return new ThreadPoolExecutor(
        4, 16, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
  }
}
