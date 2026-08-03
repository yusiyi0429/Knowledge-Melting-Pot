package com.knowledgemeltingpot.workbench.application.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface HostAddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
