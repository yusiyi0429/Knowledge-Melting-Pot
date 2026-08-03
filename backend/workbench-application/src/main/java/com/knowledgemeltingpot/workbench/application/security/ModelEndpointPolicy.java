package com.knowledgemeltingpot.workbench.application.security;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ModelEndpointPolicy {
    private final Set<String> allowedHosts;
    private final HostAddressResolver resolver;

    public ModelEndpointPolicy(Set<String> allowedHosts, HostAddressResolver resolver) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("model endpoint host whitelist must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String host : allowedHosts) {
            normalized.add(normalizeHost(host));
        }
        this.allowedHosts = Set.copyOf(normalized);
        this.resolver = resolver;
    }

    /**
     * Validate an initial URL or redirect target. Network clients must call this for every redirect.
     */
    public ValidatedModelEndpoint validate(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        final URI parsed;
        try {
            parsed = new URI(rawBaseUrl.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("baseUrl is not a valid URI", exception);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException("baseUrl must use https");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must include a host");
        }
        if (parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not include user info, query, or fragment");
        }
        if (parsed.getPort() > 65_535) {
            throw new IllegalArgumentException("baseUrl port is out of range");
        }
        String rawPath = parsed.getRawPath();
        if (rawPath != null && (rawPath.contains("%") || rawPath.contains("\\") || rawPath.startsWith("//")
                || !rawPath.equals(parsed.normalize().getRawPath()))) {
            throw new IllegalArgumentException("baseUrl path must be canonical and contain no encoded characters");
        }

        String host = normalizeHost(parsed.getHost());
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException("baseUrl host is not in the administrator whitelist");
        }
        List<InetAddress> addresses;
        try {
            addresses = List.copyOf(resolver.resolve(host));
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("baseUrl host could not be resolved", exception);
        }
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("baseUrl host did not resolve to an address");
        }
        if (addresses.stream().anyMatch(ModelEndpointPolicy::isNonPublicAddress)) {
            throw new IllegalArgumentException("baseUrl host resolves to a prohibited network address");
        }

        try {
            URI canonical = new URI("https", null, host, parsed.getPort(),
                    rawPath == null || rawPath.isEmpty() ? "/" : rawPath,
                    null, null).normalize();
            return new ValidatedModelEndpoint(canonical, addresses);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("baseUrl could not be normalized", exception);
        }
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("model endpoint host must not be blank");
        }
        String candidate = host.trim();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isBlank() || candidate.contains("*") || candidate.contains("/")) {
            throw new IllegalArgumentException("model endpoint host whitelist entries must be exact host names");
        }
        return IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }
}
