package eu.intracherche.intracherche.indexing.outage;

import java.io.IOException;

/**
 * Thrown by a caller that stopped waiting for a service to come back (admin pause, shutdown).
 * The file it was indexing is left pending and untouched: the failure is not the file's.
 */
public class ServiceUnavailableException extends IOException {

    private final ServiceOutageMonitor.Dependency dependency;

    public ServiceUnavailableException(ServiceOutageMonitor.Dependency dependency) {
        super(dependency.label + " indisponible");
        this.dependency = dependency;
    }

    public ServiceOutageMonitor.Dependency dependency() {
        return dependency;
    }
}
