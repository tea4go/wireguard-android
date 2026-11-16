/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.config.BadConfigException.Location;
import com.wireguard.config.BadConfigException.Reason;
import com.wireguard.config.BadConfigException.Section;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;
import com.wireguard.crypto.KeyPair;
import com.wireguard.util.NonNullForAll;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import androidx.annotation.Nullable;

/**
 * Represents the configuration for a WireGuard interface (an [Interface] block). Interfaces must
 * have a private key (used to initialize a {@code KeyPair}), and may optionally have several other
 * attributes.
 * <p>
 * Instances of this class are immutable.
 */
@NonNullForAll
public final class Interface {
    private static final int MAX_UDP_PORT = 65535;
    private static final int MIN_UDP_PORT = 0;
    private static final Pattern TCP_FORWARD_PATTERN = Pattern.compile(
            "^(?:((?:\\d{1,3}\\.){3}\\d{1,3}):)?(\\d+)\\s*(?:->|→)\\s*((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d+)$");
    private static final Pattern TEST_PORT_PATTERN = Pattern.compile(
            "^(?:((?:\\d{1,3}\\.){3}\\d{1,3}):)?(\\d+)$");

    private final Set<InetNetwork> addresses;
    private final Set<InetAddress> dnsServers;
    private final Set<String> dnsSearchDomains;
    private final Set<String> excludedApplications;
    private final Set<String> includedApplications;
    private final KeyPair keyPair;
    private final Optional<Integer> listenPort;
    private final Optional<Integer> mtu;
    @Nullable private final TcpForwardRule tcpForward;
    @Nullable private final TestServerRule testPort;
    private final boolean tcpForwardEnabled;
    private final boolean testServerEnabled;

    private Interface(final Builder builder) {
        // Defensively copy to ensure immutability even if the Builder is reused.
        addresses = Collections.unmodifiableSet(new LinkedHashSet<>(builder.addresses));
        dnsServers = Collections.unmodifiableSet(new LinkedHashSet<>(builder.dnsServers));
        dnsSearchDomains = Collections.unmodifiableSet(new LinkedHashSet<>(builder.dnsSearchDomains));
        excludedApplications = Collections.unmodifiableSet(new LinkedHashSet<>(builder.excludedApplications));
        includedApplications = Collections.unmodifiableSet(new LinkedHashSet<>(builder.includedApplications));
        keyPair = Objects.requireNonNull(builder.keyPair, "Interfaces must have a private key");
        listenPort = builder.listenPort;
        mtu = builder.mtu;
        tcpForward = builder.tcpForward;
        testPort = builder.testPort;
        tcpForwardEnabled = builder.tcpForwardEnabled;
        testServerEnabled = builder.testServerEnabled;
    }

    /**
     * Parses an series of "KEY = VALUE" lines into an {@code Interface}. Throws
     * {@link ParseException} if the input is not well-formed or contains unknown attributes.
     *
     * @param lines An iterable sequence of lines, containing at least a private key attribute
     * @return An {@code Interface} with all of the attributes from {@code lines} set
     */
    public static Interface parse(final Iterable<? extends CharSequence> lines)
            throws BadConfigException {
        final Builder builder = new Builder();
        for (final CharSequence line : lines) {
            final Attribute attribute = Attribute.parse(line).orElseThrow(() ->
                    new BadConfigException(Section.INTERFACE, Location.TOP_LEVEL,
                            Reason.SYNTAX_ERROR, line));
            switch (attribute.getKey().toLowerCase(Locale.ENGLISH)) {
                case "address":
                    builder.parseAddresses(attribute.getValue());
                    break;
                case "dns":
                    builder.parseDnsServers(attribute.getValue());
                    break;
                case "excludedapplications":
                    builder.parseExcludedApplications(attribute.getValue());
                    break;
                case "includedapplications":
                    builder.parseIncludedApplications(attribute.getValue());
                    break;
                case "listenport":
                    builder.parseListenPort(attribute.getValue());
                    break;
                case "mtu":
                    builder.parseMtu(attribute.getValue());
                    break;
                case "tcpforward":
                    builder.parseTcpForward(attribute.getValue());
                    break;
                case "tcpforwardenabled":
                    builder.parseTcpForwardEnabled(attribute.getValue());
                    break;
                case "testport":
                    builder.parseTestPort(attribute.getValue());
                    break;
                case "testserverenabled":
                    builder.parseTestServerEnabled(attribute.getValue());
                    break;
                case "privatekey":
                    builder.parsePrivateKey(attribute.getValue());
                    break;
                default:
                    throw new BadConfigException(Section.INTERFACE, Location.TOP_LEVEL,
                            Reason.UNKNOWN_ATTRIBUTE, attribute.getKey());
            }
        }
        return builder.build();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Interface))
            return false;
        final Interface other = (Interface) obj;
        return addresses.equals(other.addresses)
                && dnsServers.equals(other.dnsServers)
                && dnsSearchDomains.equals(other.dnsSearchDomains)
                && excludedApplications.equals(other.excludedApplications)
                && includedApplications.equals(other.includedApplications)
                && keyPair.equals(other.keyPair)
                && listenPort.equals(other.listenPort)
                && mtu.equals(other.mtu)
                && Objects.equals(tcpForward, other.tcpForward)
                && Objects.equals(testPort, other.testPort)
                && tcpForwardEnabled == other.tcpForwardEnabled
                && testServerEnabled == other.testServerEnabled;
    }

    /**
     * Returns the set of IP addresses assigned to the interface.
     *
     * @return a set of {@link InetNetwork}s
     */
    public Set<InetNetwork> getAddresses() {
        // The collection is already immutable.
        return addresses;
    }

    /**
     * Returns the set of DNS servers associated with the interface.
     *
     * @return a set of {@link InetAddress}es
     */
    public Set<InetAddress> getDnsServers() {
        // The collection is already immutable.
        return dnsServers;
    }

    /**
     * Returns the set of DNS search domains associated with the interface.
     *
     * @return a set of strings
     */
    public Set<String> getDnsSearchDomains() {
        // The collection is already immutable.
        return dnsSearchDomains;
    }

    /**
     * Returns the set of applications excluded from using the interface.
     *
     * @return a set of package names
     */
    public Set<String> getExcludedApplications() {
        // The collection is already immutable.
        return excludedApplications;
    }

    /**
     * Returns the set of applications included exclusively for using the interface.
     *
     * @return a set of package names
     */
    public Set<String> getIncludedApplications() {
        // The collection is already immutable.
        return includedApplications;
    }

    /**
     * Returns the public/private key pair used by the interface.
     *
     * @return a key pair
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Returns the UDP port number that the WireGuard interface will listen on.
     *
     * @return a UDP port number, or {@code Optional.empty()} if none is configured
     */
    public Optional<Integer> getListenPort() {
        return listenPort;
    }

    /**
     * Returns the MTU used for the WireGuard interface.
     *
     * @return the MTU, or {@code Optional.empty()} if none is configured
     */
    public Optional<Integer> getMtu() {
        return mtu;
    }

    /**
     * Returns the TCP forwarding rule associated with the interface.
     *
     * @return a {@link TcpForwardRule}, or {@code null} if none is configured
     */
    @Nullable
    public TcpForwardRule getTcpForward() {
        return tcpForward;
    }

    /**
     * Returns the test server rule associated with the interface.
     *
     * @return a {@link TestServerRule}, or {@code null} if none is configured
     */
    @Nullable
    public TestServerRule getTestPort() {
        return testPort;
    }

    public boolean getTcpForwardEnabled() {
        return tcpForwardEnabled;
    }

    public boolean getTestServerEnabled() {
        return testServerEnabled;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = 31 * hash + addresses.hashCode();
        hash = 31 * hash + dnsServers.hashCode();
        hash = 31 * hash + excludedApplications.hashCode();
        hash = 31 * hash + includedApplications.hashCode();
        hash = 31 * hash + keyPair.hashCode();
        hash = 31 * hash + listenPort.hashCode();
        hash = 31 * hash + mtu.hashCode();
        hash = 31 * hash + Objects.hashCode(tcpForward);
        hash = 31 * hash + Objects.hashCode(testPort);
        hash = 31 * hash + Boolean.hashCode(tcpForwardEnabled);
        hash = 31 * hash + Boolean.hashCode(testServerEnabled);
        return hash;
    }

    /**
     * Converts the {@code Interface} into a string suitable for debugging purposes. The {@code
     * Interface} is identified by its public key and (if set) the port used for its UDP socket.
     *
     * @return A concise single-line identifier for the {@code Interface}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("(Interface ");
        sb.append(keyPair.getPublicKey().toBase64());
        listenPort.ifPresent(lp -> sb.append(" @").append(lp));
        sb.append(')');
        return sb.toString();
    }

    /**
     * Converts the {@code Interface} into a string suitable for inclusion in a {@code wg-quick}
     * configuration file.
     *
     * @return The {@code Interface} represented as a series of "Key = Value" lines
     */
    public String toWgQuickString() {
        final StringBuilder sb = new StringBuilder();
        if (!addresses.isEmpty())
            sb.append("Address = ").append(Attribute.join(addresses)).append('\n');
        if (!dnsServers.isEmpty()) {
            final List<String> dnsServerStrings = dnsServers.stream().map(InetAddress::getHostAddress).collect(Collectors.toList());
            dnsServerStrings.addAll(dnsSearchDomains);
            sb.append("DNS = ").append(Attribute.join(dnsServerStrings)).append('\n');
        }
        if (!excludedApplications.isEmpty())
            sb.append("ExcludedApplications = ").append(Attribute.join(excludedApplications)).append('\n');
        if (!includedApplications.isEmpty())
            sb.append("IncludedApplications = ").append(Attribute.join(includedApplications)).append('\n');
        listenPort.ifPresent(lp -> sb.append("ListenPort = ").append(lp).append('\n'));
        mtu.ifPresent(m -> sb.append("MTU = ").append(m).append('\n'));
        if (testPort != null) {
            sb.append("TestPort = ").append(testPort).append('\n');
            sb.append("TestServerEnabled = ").append(testServerEnabled).append('\n');
        }
        if (tcpForward != null) {
            sb.append("TcpForward = ").append(tcpForward).append('\n');
            sb.append("TcpForwardEnabled = ").append(tcpForwardEnabled).append('\n');
        }
        sb.append("PrivateKey = ").append(keyPair.getPrivateKey().toBase64()).append('\n');
        return sb.toString();
    }

    /**
     * Serializes the {@code Interface} for use with the WireGuard cross-platform userspace API.
     * Note that not all attributes are included in this representation.
     *
     * @return the {@code Interface} represented as a series of "KEY=VALUE" lines
     */
    public String toWgUserspaceString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("private_key=").append(keyPair.getPrivateKey().toHex()).append('\n');
        listenPort.ifPresent(lp -> sb.append("listen_port=").append(lp).append('\n'));
        return sb.toString();
    }

    @SuppressWarnings("UnusedReturnValue")
    public static final class Builder {
        // Defaults to an empty set.
        private final Set<InetNetwork> addresses = new LinkedHashSet<>();
        // Defaults to an empty set.
        private final Set<InetAddress> dnsServers = new LinkedHashSet<>();
        // Defaults to an empty set.
        private final Set<String> dnsSearchDomains = new LinkedHashSet<>();
        // Defaults to an empty set.
        private final Set<String> excludedApplications = new LinkedHashSet<>();
        // Defaults to an empty set.
        private final Set<String> includedApplications = new LinkedHashSet<>();
        // No default; must be provided before building.
        @Nullable private KeyPair keyPair;
        // Defaults to not present.
        private Optional<Integer> listenPort = Optional.empty();
        // Defaults to not present.
        private Optional<Integer> mtu = Optional.empty();
        // Defaults to not present.
        @Nullable private TcpForwardRule tcpForward;
        // Defaults to not present.
        @Nullable private TestServerRule testPort;
        private boolean tcpForwardEnabled;
        private boolean testServerEnabled;

        public Builder addAddress(final InetNetwork address) {
            addresses.add(address);
            return this;
        }

        public Builder addAddresses(final Collection<InetNetwork> addresses) {
            this.addresses.addAll(addresses);
            return this;
        }

        public Builder addDnsServer(final InetAddress dnsServer) {
            dnsServers.add(dnsServer);
            return this;
        }

        public Builder addDnsServers(final Collection<? extends InetAddress> dnsServers) {
            this.dnsServers.addAll(dnsServers);
            return this;
        }

        public Builder addDnsSearchDomain(final String dnsSearchDomain) {
            dnsSearchDomains.add(dnsSearchDomain);
            return this;
        }

        public Builder addDnsSearchDomains(final Collection<String> dnsSearchDomains) {
            this.dnsSearchDomains.addAll(dnsSearchDomains);
            return this;
        }

        public Interface build() throws BadConfigException {
            if (keyPair == null)
                throw new BadConfigException(Section.INTERFACE, Location.PRIVATE_KEY,
                        Reason.MISSING_ATTRIBUTE, null);
            if (!includedApplications.isEmpty() && !excludedApplications.isEmpty())
                throw new BadConfigException(Section.INTERFACE, Location.INCLUDED_APPLICATIONS,
                        Reason.INVALID_KEY, null);
            return new Interface(this);
        }

        public Builder excludeApplication(final String application) {
            excludedApplications.add(application);
            return this;
        }

        public Builder excludeApplications(final Collection<String> applications) {
            excludedApplications.addAll(applications);
            return this;
        }

        public Builder includeApplication(final String application) {
            includedApplications.add(application);
            return this;
        }

        public Builder includeApplications(final Collection<String> applications) {
            includedApplications.addAll(applications);
            return this;
        }

        public Builder parseAddresses(final CharSequence addresses) throws BadConfigException {
            try {
                for (final String address : Attribute.split(addresses))
                    addAddress(InetNetwork.parse(address));
                return this;
            } catch (final ParseException e) {
                throw new BadConfigException(Section.INTERFACE, Location.ADDRESS, e);
            }
        }

        public Builder parseDnsServers(final CharSequence dnsServers) throws BadConfigException {
            try {
                for (final String dnsServer : Attribute.split(dnsServers)) {
                    try {
                        addDnsServer(InetAddresses.parse(dnsServer));
                    } catch (final ParseException e) {
                        if (e.getParsingClass() != InetAddress.class || !InetAddresses.isHostname(dnsServer))
                            throw e;
                        addDnsSearchDomain(dnsServer);
                    }
                }
                return this;
            } catch (final ParseException e) {
                throw new BadConfigException(Section.INTERFACE, Location.DNS, e);
            }
        }

        public Builder parseExcludedApplications(final CharSequence apps) {
            return excludeApplications(List.of(Attribute.split(apps)));
        }

        public Builder parseIncludedApplications(final CharSequence apps) {
            return includeApplications(List.of(Attribute.split(apps)));
        }

        public Builder parseListenPort(final String listenPort) throws BadConfigException {
            try {
                return setListenPort(Integer.parseInt(listenPort));
            } catch (final NumberFormatException e) {
                throw new BadConfigException(Section.INTERFACE, Location.LISTEN_PORT, listenPort, e);
            }
        }

        public Builder parseMtu(final String mtu) throws BadConfigException {
            try {
                return setMtu(Integer.parseInt(mtu));
            } catch (final NumberFormatException e) {
                throw new BadConfigException(Section.INTERFACE, Location.MTU, mtu, e);
            }
        }

        public Builder parseTcpForward(final String value) throws BadConfigException {
            final Matcher match = TCP_FORWARD_PATTERN.matcher(value.trim());
            if (!match.matches())
                throw new BadConfigException(Section.INTERFACE, Location.TCP_FORWARD,
                        Reason.INVALID_VALUE, value);
            final String listenIp = match.group(1) == null ? null
                    : validateIpv4Octets(match.group(1), value, Location.TCP_FORWARD);
            final int listenPort = parseRequiredPort(Integer.parseInt(match.group(2)), Location.TCP_FORWARD);
            final String targetIp = validateIpv4Octets(match.group(3), value, Location.TCP_FORWARD);
            final int targetPort = parseRequiredPort(Integer.parseInt(match.group(4)), Location.TCP_FORWARD);
            this.tcpForward = new TcpForwardRule(listenIp, listenPort, targetIp, targetPort);
            return this;
        }

        public Builder parseTcpForwardEnabled(final String value) {
            this.tcpForwardEnabled = value.trim().equalsIgnoreCase("true");
            return this;
        }

        public Builder parseTestPort(final String value) throws BadConfigException {
            final Matcher match = TEST_PORT_PATTERN.matcher(value.trim());
            if (!match.matches())
                throw new BadConfigException(Section.INTERFACE, Location.TEST_PORT,
                        Reason.INVALID_VALUE, value);
            final String listenIp = match.group(1) == null ? null
                    : validateIpv4Octets(match.group(1), value, Location.TEST_PORT);
            final int port = parseRequiredPort(Integer.parseInt(match.group(2)), Location.TEST_PORT);
            this.testPort = new TestServerRule(listenIp, port);
            return this;
        }

        public Builder parseTestServerEnabled(final String value) {
            this.testServerEnabled = value.trim().equalsIgnoreCase("true");
            return this;
        }

        private static String validateIpv4Octets(final String ip, final String value,
                                                 final Location location) throws BadConfigException {
            for (final String octet : ip.split("\\."))
                if (Integer.parseInt(octet) > 255)
                    throw new BadConfigException(Section.INTERFACE, location,
                            Reason.INVALID_VALUE, value);
            return ip;
        }

        private static int parseRequiredPort(final int port, final Location location)
                throws BadConfigException {
            if (port <= MIN_UDP_PORT || port > MAX_UDP_PORT)
                throw new BadConfigException(Section.INTERFACE, location,
                        Reason.INVALID_VALUE, String.valueOf(port));
            return port;
        }

        public Builder parsePrivateKey(final String privateKey) throws BadConfigException {
            try {
                return setKeyPair(new KeyPair(Key.fromBase64(privateKey)));
            } catch (final KeyFormatException e) {
                throw new BadConfigException(Section.INTERFACE, Location.PRIVATE_KEY, e);
            }
        }

        public Builder setKeyPair(final KeyPair keyPair) {
            this.keyPair = keyPair;
            return this;
        }

        public Builder setListenPort(final int listenPort) throws BadConfigException {
            if (listenPort < MIN_UDP_PORT || listenPort > MAX_UDP_PORT)
                throw new BadConfigException(Section.INTERFACE, Location.LISTEN_PORT,
                        Reason.INVALID_VALUE, String.valueOf(listenPort));
            this.listenPort = listenPort == 0 ? Optional.empty() : Optional.of(listenPort);
            return this;
        }

        public Builder setMtu(final int mtu) throws BadConfigException {
            if (mtu < 0)
                throw new BadConfigException(Section.INTERFACE, Location.LISTEN_PORT,
                        Reason.INVALID_VALUE, String.valueOf(mtu));
            this.mtu = mtu == 0 ? Optional.empty() : Optional.of(mtu);
            return this;
        }
    }
}
