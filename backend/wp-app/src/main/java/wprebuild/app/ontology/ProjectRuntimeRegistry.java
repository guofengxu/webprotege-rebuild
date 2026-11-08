package wprebuild.app.ontology;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import wprebuild.kernel.id.ProjectId;

/**
 * Loads a project on demand and drops it after it has been idle (ADR-006, WP-8).
 *
 * <p>A corrupted runtime is removed and loaded again from the revision log.
 */
@Component
public final class ProjectRuntimeRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProjectRuntimeRegistry.class);

    private final Function<ProjectId, ProjectHandle> loader;

    private final LoadingCache<ProjectId, ProjectHandle> cache;

    private final Timer loadTimer;

    /** Last handle for each project, including one the cache has already dropped. */
    private final ConcurrentHashMap<ProjectId, ProjectHandle> active = new ConcurrentHashMap<>();

    private final ExecutorService retirement = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("wp-runtime-retire-", 0).factory());

    @Autowired
    public ProjectRuntimeRegistry(
            ProjectRuntimeFactory factory,
            RuntimeProperties properties,
            Ticker ticker,
            ObjectProvider<MeterRegistry> meters) {
        this(factory::create, properties, ticker, registry(meters));
    }

    private static MeterRegistry registry(ObjectProvider<MeterRegistry> meters) {
        Objects.requireNonNull(meters, "meters");
        return meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    private ProjectRuntimeRegistry(
            Function<ProjectId, ProjectHandle> loader,
            RuntimeProperties properties,
            Ticker ticker,
            MeterRegistry meters) {
        this.loader = Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(meters, "meters");
        this.loadTimer = Timer.builder("wp.runtime.load").register(meters);
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .scheduler(Scheduler.systemScheduler())
                .expireAfterAccess(properties.idleTimeout())
                .maximumSize(properties.maxLoaded())
                .removalListener(this::onRemoval)
                .build(this::load);
        Gauge.builder("wp.runtime.loaded", cache, value -> (double) value.estimatedSize())
                .register(meters);
    }

    /** Package-visible so tests can count loads and supply a fake ticker. */
    static ProjectRuntimeRegistry withLoader(
            Function<ProjectId, ProjectHandle> loader,
            RuntimeProperties properties,
            Ticker ticker,
            MeterRegistry meters) {
        return new ProjectRuntimeRegistry(loader, properties, ticker, meters);
    }

    public ProjectHandle get(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        ProjectHandle handle = current(projectId);
        if (!handle.runtime().isCorrupted()) {
            return handle;
        }
        log.warn("reloading corrupted project runtime {}", projectId);
        cache.asMap().remove(projectId, handle);
        ProjectHandle reloaded = current(projectId);
        if (reloaded.runtime().isCorrupted()) {
            throw new IllegalStateException("reloaded project runtime is corrupted: " + projectId);
        }
        return reloaded;
    }

    public void evict(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        retire(active.get(projectId));
        cache.invalidate(projectId);
    }

    @Override
    public void destroy() {
        retirement.shutdown();
    }

    private ProjectHandle current(ProjectId projectId) {
        cache.cleanUp();
        return cache.get(projectId);
    }

    private ProjectHandle load(ProjectId projectId) {
        // ADR-006: an evicted handle may still be inside apply. Retire it under its generation
        // lock before reading the log, so that commit finishes first and then cannot write again.
        retire(active.get(projectId));
        ProjectHandle created = loadTimer.record(() -> loader.apply(projectId));
        if (created == null) {
            throw new IllegalStateException("project runtime loader returned null for " + projectId);
        }
        active.put(projectId, created);
        return created;
    }

    private void onRemoval(ProjectId projectId, ProjectHandle handle, RemovalCause cause) {
        if (handle == null) {
            return;
        }
        if (cause.wasEvicted()) {
            log.info(
                    "evicted project runtime {} at revision {} ({})",
                    projectId,
                    handle.runtime().head().value(),
                    cause);
        }
        if (!cause.wasEvicted() && cause != RemovalCause.EXPLICIT) {
            return;
        }
        try {
            retirement.execute(() -> {
                retire(handle);
                active.remove(projectId, handle);
            });
        } catch (RuntimeException failure) {
            log.warn("could not retire project runtime {}", projectId, failure);
        }
    }

    private static void retire(ProjectHandle handle) {
        if (handle == null || handle.runtime().isCorrupted()) {
            return;
        }
        handle.gate().generate(() -> {
            handle.runtime().markCorrupted();
            return null;
        });
    }
}
