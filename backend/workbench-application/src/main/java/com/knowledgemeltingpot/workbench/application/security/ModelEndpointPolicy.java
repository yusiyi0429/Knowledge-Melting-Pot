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
import java.util.Optional;
import java.util.Set;
import com.knowledgemeltingpot.workbench.application.port.ModelEndpointRuleSource;
import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;

public final class ModelEndpointPolicy {
    private final Set<String> bootstrapHosts;
    private final ModelEndpointRuleSource ruleSource;
    private final HostAddressResolver resolver;

    public ModelEndpointPolicy(Set<String> allowedHosts, HostAddressResolver resolver) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("model endpoint host whitelist must not be empty");
        }
        this.bootstrapHosts = normalizeHosts(allowedHosts);
        this.ruleSource = host -> Optional.empty();
        this.resolver = resolver;
    }

    public ModelEndpointPolicy(Set<String> bootstrapHosts, ModelEndpointRuleSource ruleSource,
            HostAddressResolver resolver) {
        this.bootstrapHosts = normalizeHosts(bootstrapHosts == null ? Set.of() : bootstrapHosts);
        this.ruleSource = java.util.Objects.requireNonNull(ruleSource, "model endpoint rule source is required");
        this.resolver = java.util.Objects.requireNonNull(resolver, "host resolver is required");
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            normalized.add(normalizeConfiguredHost(host));
        }
        return Set.copyOf(normalized);
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
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Base URL 仅支持 http:// 或 https://");
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

        String host = normalizeConfiguredHost(parsed.getHost());
        Optional<ModelEndpointRule> managedRule = ruleSource.findByNormalizedHost(host);
        boolean bootstrapRule = bootstrapHosts.contains(host);
        if (managedRule.isEmpty() && !bootstrapRule) {
            throw new IllegalArgumentException("模型主机 " + host + " 尚未加入可信列表，请先在“模型访问策略”中配置");
        }
        boolean allowHttp = managedRule.map(ModelEndpointRule::allowHttp).orElse(false);
        boolean allowPrivateAddresses = managedRule.map(ModelEndpointRule::allowPrivateAddresses).orElse(false);
        Set<Integer> allowedPorts = managedRule.map(ModelEndpointRule::allowedPorts).orElse(Set.of(443));
        int effectivePort = parsed.getPort() >= 0 ? parsed.getPort() : (scheme.equals("https") ? 443 : 80);
        if (scheme.equals("http") && !allowHttp) {
            throw new IllegalArgumentException("可信主机 " + host + " 未允许使用 HTTP");
        }
        if (!allowedPorts.contains(effectivePort)) {
            throw new IllegalArgumentException("端口 " + effectivePort + " 未包含在可信主机 " + host + " 的允许端口中");
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
        if (addresses.stream().anyMatch(ModelEndpointPolicy::isAlwaysProhibitedAddress)) {
            throw new IllegalArgumentException("模型主机解析到了禁止访问的回环、链路本地或保留地址");
        }
        if (!allowPrivateAddresses && addresses.stream().anyMatch(ModelEndpointPolicy::isPrivateAddress)) {
            throw new IllegalArgumentException("可信主机 " + host + " 尚未允许访问内网地址");
        }

        try {
            URI canonical = new URI(scheme, null, host, parsed.getPort(),
                    rawPath == null || rawPath.isEmpty() ? "/" : rawPath,
                    null, null).normalize();
            return new ValidatedModelEndpoint(canonical, addresses);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("baseUrl could not be normalized", exception);
        }
    }

    public static String normalizeConfiguredHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("model endpoint host must not be blank");
        }
        String candidate = host.trim();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isBlank() || candidate.contains("*") || candidate.contains("/")
                || candidate.contains(":") || candidate.length() > 253) {
            throw new IllegalArgumentException("可信模型主机必须是精确的 DNS 主机名或 IPv4 地址，不包含协议、路径、通配符或端口");
        }
        try {
            return IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("可信模型主机格式无效", exception);
        }
    }

    private static boolean isAlwaysProhibitedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean documentation = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return documentation;
        }
        return true;
    }

    private static boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            return (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
        }
        return address.isSiteLocalAddress();
    }
}
