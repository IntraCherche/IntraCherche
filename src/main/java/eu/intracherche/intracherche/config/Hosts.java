package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Locale;

/** Whether a service URL points at this machine. */
public final class Hosts {

    private static final Logger log = LoggerFactory.getLogger(Hosts.class);

    private Hosts() {}

    /**
     * True when the URL names this machine the way a default does: empty, localhost, 127.*, ::1.
     * No DNS lookup, so it is cheap enough to be asked on every request.
     */
    public static boolean isLoopbackText(String url) {
        String host = hostOf(url);
        return host == null || host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
    }

    /**
     * True when the URL points at this machine, including by its own name or LAN address.
     *
     * <p>Every answer but a confident "that is another host" is local. Matching "localhost" and
     * 127.* as text would miss a server that names itself — ic-server.corp.local, or its own LAN
     * address. A name that will not resolve counts as local too: callers use this to decide what
     * to reserve or start here, and wrongly assuming "elsewhere" is the expensive mistake.
     */
    public static boolean isThisMachine(String url) {
        if (isLoopbackText(url)) return true;
        String host = hostOf(url);
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
                if (NetworkInterface.getByInetAddress(address) != null) return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Could not resolve host '{}' — assuming it is this machine", host);
            return true;
        }
    }

    /** Lower-cased host without IPv6 brackets, or null when there is none to read. */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return null;
            return host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        } catch (Exception e) {
            log.warn("Could not read URL '{}' — assuming it points at this machine", url);
            return null;
        }
    }
}
