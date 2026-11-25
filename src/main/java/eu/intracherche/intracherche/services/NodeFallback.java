package eu.intracherche.intracherche.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether to call a service on the GPU node or the same service on this server.
 *
 * <p>The node's owner may stop its services to use the card for something else, or the machine
 * may be asleep. Calls then go to this server's processor, more slowly, instead of failing. After
 * a failure the node is left alone for {@link #RETRY_AFTER}, so that a machine that is off costs
 * one connection timeout a minute rather than one per call, and it takes the calls back on its own
 * once it answers again.
 */
public final class NodeFallback {

    public static final Duration RETRY_AFTER = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(NodeFallback.class);

    /** What the node serves, in log lines: "reranker", "Sherlock's model". */
    private final String service;
    private final String nodeUrl;
    private final Clock clock;
    /** While in the future, calls skip the node. */
    private volatile Instant skippedUntil = Instant.MIN;
    /** Whether the last call to the node was served there: logs each change once. */
    private final AtomicBoolean answering = new AtomicBoolean(true);

    public NodeFallback(String service, String nodeUrl, Clock clock) {
        this.service = service;
        this.nodeUrl = nodeUrl;
        this.clock = clock;
    }

    public String nodeUrl() {
        return nodeUrl;
    }

    /** Whether to try the node now: it served the last call, or its minute of rest is over. */
    public boolean worthTrying() {
        return !clock.instant().isBefore(skippedUntil);
    }

    public void answered() {
        if (!answering.getAndSet(true)) {
            log.info("The GPU node's {} at {} answers again: it takes the calls back.", service, nodeUrl);
        }
    }

    public void failed(Throwable e) {
        failed(OcrService.rootCause(e));
    }

    public void failed(String cause) {
        skippedUntil = clock.instant().plus(RETRY_AFTER);
        if (answering.getAndSet(false)) {
            log.warn("The GPU node's {} at {} did not answer ({}): this server's processor takes over, "
                    + "much more slowly, and the node is tried again every {} s.",
                    service, nodeUrl, cause, RETRY_AFTER.toSeconds());
        }
    }
}
