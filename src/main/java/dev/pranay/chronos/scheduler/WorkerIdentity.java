package dev.pranay.chronos.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * This JVM's worker id, stable for the process lifetime.
 *
 * <p>It is what the lease is held under, so it has to be unique across every process touching the
 * database. Two workers sharing an id would each pass the other's ownership check and the
 * conditional write-back in {@code JobRepositoryCustomImpl} would silently stop protecting
 * anything — the failure mode it exists to prevent, reintroduced through the back door.
 *
 * <p>Hence hostname <em>plus</em> a random suffix rather than hostname alone: in Docker the
 * hostname is the container id and already unique, but on a developer machine running two
 * instances it is not.
 */
@Component
public class WorkerIdentity {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdentity.class);

    private final String id;

    public WorkerIdentity() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        String prefix = (host == null || host.isBlank()) ? "worker" : host.toLowerCase();
        if (prefix.length() > 20) {
            prefix = prefix.substring(0, 20);
        }
        this.id = prefix + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff));
        log.info("Worker identity: {}", id);
    }

    public String id() {
        return id;
    }
}
