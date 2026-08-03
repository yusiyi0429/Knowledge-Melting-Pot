package com.knowledgemeltingpot.workbench.application.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

public record ValidatedModelEndpoint(URI uri, List<InetAddress> resolvedAddresses) {
    public ValidatedModelEndpoint {
        resolvedAddresses = List.copyOf(resolvedAddresses);
    }
}
