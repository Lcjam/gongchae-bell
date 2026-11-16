package kr.gongchae.bell.alerts;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * ADR-0009: right before a webhook request, every address the host resolves to must be public. Loopback, any-local,
 * link-local, site-local (private), multicast, CGNAT 100.64/10, 0/8, benchmarking 198.18/15, broadcast and IPv6 ULA
 * fc00::/7 are refused. IPv4-mapped IPv6 arrives as Inet4Address and gets the IPv4 checks. A failed lookup refuses.
 */
public final class AddressGuard {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private final Resolver resolver;
    public AddressGuard(Resolver resolver) { this.resolver = resolver; }

    public boolean allows(String host) {
        InetAddress[] addresses;
        try { addresses = resolver.resolve(host); } catch (UnknownHostException | RuntimeException failure) { return false; }
        if (addresses == null || addresses.length == 0) return false;
        for (InetAddress address : addresses) if (!isPublic(address)) return false;
        return true;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first != 0 && first != 255 && !(first == 100 && (second & 0xc0) == 64) && !(first == 198 && (second == 18 || second == 19));
        }
        return (b[0] & 0xfe) != 0xfc;
    }
}
