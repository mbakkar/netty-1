/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DnsEntry;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.Question;
import io.netty.handler.dns.decoder.record.MailExchangerRecord;
import io.netty.handler.dns.decoder.record.ServiceRecord;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * This is the main class for looking up information from DNS servers. Users
 * should call the methods in this class to query and receive answers from DNS
 * servers. The class attempts to load user's default DNS servers, but also
 * includes Google and OpenDNS DNS servers.
 */
public final class DnsExchangeFactory {

    /**
     * How long to wait for an answer from a DNS server after sending a query
     * before timing out and moving on to the next DNS server.
     */
    public static final long REQUEST_TIMEOUT = 2000;

    private static final EventExecutorGroup executor = new DefaultEventExecutorGroup(2);
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DnsExchangeFactory.class);
    private static final List<byte[]> dnsServers = new ArrayList<byte[]>();
    private static final Map<byte[], Channel> dnsServerChannels = new HashMap<byte[], Channel>();
    private static final Object idxLock = new Object();

    private static int idx;

    static {
        dnsServers.add(new byte[] { 8, 8, 8, 8 }); // Google DNS servers
        dnsServers.add(new byte[] { 8, 8, 4, 4 });
        dnsServers.add(new byte[] { -48, 67, -34, -34 }); // OpenDNS servers
        dnsServers.add(new byte[] { -48, 67, -36, -36 });
        Thread thread = new Thread() {

            @Override
            public void run() {
                try {
                    Class<?> configClass = Class.forName("sun.net.dns.ResolverConfiguration");
                    Method open = configClass.getMethod("open");
                    Method nameservers = configClass.getMethod("nameservers");
                    Object instance = open.invoke(null);
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) nameservers.invoke(instance);
                    for (String dns : list) {
                        String[] parts = dns.split("\\.");
                        if (parts.length == 4 || parts.length == 16) {
                            byte[] address = new byte[parts.length];
                            for (int i = 0; i < address.length; i++) {
                                address[i] = (byte) Integer.parseInt(parts[i]);
                            }
                            if (validAddress(address)) {
                                dnsServers.add(address);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to obtain system's DNS server addresses, using defaults only.", e);
                }
            }
        };
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Checks if a DNS server can actually be connected to and queried for
     * information by running through a request. This is a
     * <strong>blocking</strong> method.
     *
     * @param address
     *            the DNS server being checked
     * @return {@code true} if the DNS server provides a valid response
     */
    public static boolean validAddress(byte[] address) {
        try {
            int id = obtainId();
            Channel channel = channelForAddress(address);
            Callable<List<ByteBuf>> callback = new DnsCallback<List<ByteBuf>>(-1, sendQuery(DnsEntry.TYPE_A,
                    "google.com", id, channel));
            return callback.call() != null;
        } catch (Exception e) {
            removeChannel(dnsServerChannels.get(address));
            if (logger.isWarnEnabled()) {
                StringBuilder string = new StringBuilder();
                for (int i = 0; i < address.length; i++) {
                    string.append(address[i]).append(".");
                }
                logger.warn("Failed to add DNS server " + string.substring(0, string.length() - 1), e);
            }
            return false;
        }
    }

    /**
     * Returns an id in the range 0-65536 while reducing collisions.
     */
    public static int obtainId() {
        synchronized (idxLock) {
            return idx = idx + 1 & 0xffff;
        }
    }

    /**
     * Writes a {@link DnsQuery} to a specified channel.
     *
     * @param type
     *            the type for the {@link DnsQuery}
     * @param domain
     *            the domain being queried
     * @param id
     *            the id for the {@link DnsQuery}
     * @param channel
     *            the channel the {@link DnsQuery} is written to
     * @return the {@link DnsQuery} being written
     * @throws InterruptedException
     */
    private static DnsQuery sendQuery(int type, String domain, int id, Channel channel) throws InterruptedException {
        DnsQuery query = new DnsQuery(id);
        query.addQuestion(new Question(domain, type));
        channel.writeAndFlush(query).sync();
        return query;
    }

    /**
     * Adds a DNS server to the default {@link List} of DNS servers used.
     *
     * @param dnsServerAddress
     *            the DNS server being added
     * @return {@code true} if the DNS server was added successfully
     */
    public static boolean addDnsServer(byte[] dnsServerAddress) {
        return dnsServers.add(dnsServerAddress);
    }

    /**
     * Removes a DNS server from the default {@link List} of DNS servers.
     *
     * @param dnsServerAddress
     *            the DNS server being removed
     * @return {@code true} if the DNS server was removed successfully
     */
    public static boolean removeDnsServer(byte[] dnsServerAddress) {
        return dnsServers.remove(dnsServerAddress);
    }

    /**
     * Returns the DNS server address at the specified {@code index} in the
     * {@link List}.
     */
    public static byte[] getDnsServer(int index) {
        if (index > -1 && index < dnsServers.size()) {
            return dnsServers.get(index);
        }
        return null;
    }

    /**
     * Creates a channel for a DNS server if it does not already exist, or else
     * returns the existing channel. Internal use only.
     *
     * @param dnsServerAddress
     *            the address of the DNS server
     * @return the {@link Channel} created
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    protected static Channel channelForAddress(byte[] dnsServerAddress) throws UnknownHostException, SocketException,
            InterruptedException {
        Channel channel = null;
        if ((channel = dnsServerChannels.get(dnsServerAddress)) != null) {
            return channel;
        } else {
            synchronized (dnsServerChannels) {
                if ((channel = dnsServerChannels.get(dnsServerAddress)) == null) {
                    InetAddress address = InetAddress.getByAddress(dnsServerAddress);
                    Bootstrap b = new Bootstrap();
                    b.group(new NioEventLoopGroup()).channel(NioDatagramChannel.class).remoteAddress(address, 53)
                            .option(ChannelOption.SO_BROADCAST, true).option(ChannelOption.SO_SNDBUF, 1048576)
                            .option(ChannelOption.SO_RCVBUF, 1048576).handler(new DnsClientInitializer());
                    dnsServerChannels.put(dnsServerAddress, channel = b.connect().sync().channel());
                }
                return channel;
            }
        }
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List}
     * either an IPv4 or IPv6 address for the specified domain.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static <T> Future<T> lookup(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolveSingle(domain, dnsServers.get(0), DnsEntry.TYPE_A, DnsEntry.TYPE_AAAA);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * either an IPv4 or IPv6 address for the specified domain, depending on the
     * {@code family}.
     *
     * @param family
     *            {@code 4} for IPv4 addresses, {@code 6} for IPv6 addresses, or
     *            {@code null} for both
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static <T> Future<List<T>> lookup(String domain, Integer family) throws UnknownHostException,
            SocketException, InterruptedException {
        if (family != null && family != 4 && family != 6) {
            throw new IllegalArgumentException("Family must be 4, 6, or null to indicate both 4 and 6.");
        }
        if (family == null) {
            return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_A, DnsEntry.TYPE_AAAA);
        } else if (family == 4) {
            return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_A);
        }
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_AAAA);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a
     * <strong>single</strong> resource record with one of the specified
     * {@code types}.
     *
     * @param domain
     *            the domain name being queried
     * @param dnsServerAddress
     *            the DNS server to use
     * @param types
     *            the desired resource record (only <strong>one</strong> type
     *            and one record can be returned in a single method call. The
     *            first valid resource record in the array of types will be
     *            returned)
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static <T> Future<T> resolveSingle(String domain, byte[] dnsServerAddress, int... types)
            throws UnknownHostException, SocketException, InterruptedException {
        for (int i = 0; i < types.length; i++) {
            T result = ResourceCache.getRecord(domain, types[i]);
            if (result != null) {
                return executor.next().newSucceededFuture(result);
            }
        }
        int id = obtainId();
        Channel channel = channelForAddress(dnsServerAddress);
        DnsQuery[] queries = new DnsQuery[types.length];
        for (int i = 0; i < types.length; i++) {
            queries[i] = sendQuery(types[i], domain, id, channel);
        }
        return executor.submit(new SingleResultCallback<T>(new DnsCallback<List<T>>(dnsServers
                .indexOf(dnsServerAddress), queries)));
    }

    /**
     * Returns a {@link Future} which can be used to obtain a <strong>
     * {@link List}</strong> of resource records with one of the specified
     * {@code types}.
     *
     * @param domain
     *            the domain name being queried
     * @param dnsServerAddress
     *            the DNS server to use
     * @param types
     *            the desired resource records (only <strong>one</strong> type
     *            can be returned, but multiple resource records, in a single
     *            method call. The first valid type of resource records will be
     *            returned in a {@link List})
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static <T extends List<?>> Future<T> resolve(String domain, byte[] dnsServerAddress, int... types)
            throws UnknownHostException, SocketException, InterruptedException {
        for (int i = 0; i < types.length; i++) {
            @SuppressWarnings("unchecked")
            T result = (T) ResourceCache.getRecords(domain, types[i]);
            if (result != null) {
                return executor.next().newSucceededFuture(result);
            }
        }
        int id = obtainId();
        Channel channel = channelForAddress(dnsServerAddress);
        DnsQuery[] queries = new DnsQuery[types.length];
        for (int i = 0; i < types.length; i++) {
            queries[i] = sendQuery(types[i], domain, id, channel);
        }
        return executor.submit(new DnsCallback<T>(dnsServers.indexOf(dnsServerAddress), queries));
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * IPv4 addresses as {@link ByteBuf}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<ByteBuf>> resolve4(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_A);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * IPv6 addresses as {@link ByteBuf}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<ByteBuf>> resolve6(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_AAAA);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * mail exchanger records as {@link MailExchangerRecord}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<MailExchangerRecord>> resolveMx(String domain) throws UnknownHostException,
            SocketException, InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_MX);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * service records as {@link ServiceRecord}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<ServiceRecord>> resolveSrv(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_SRV);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * text records as {@link String}s in a {@link List}.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<List<String>>> resolveTxt(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_TXT);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * canonical name records as {@link String}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<String>> resolveCname(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_CNAME);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * name server records as {@link String}s.
     *
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<String>> resolveNs(String domain) throws UnknownHostException, SocketException,
            InterruptedException {
        return resolve(domain, dnsServers.get(0), DnsEntry.TYPE_NS);
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * domain names as {@link String}s when given their corresponding IP
     * address.
     *
     * @param ipAddress
     *            the ip address to perform a reverse lookup on
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<String>> reverse(byte[] ipAddress) throws UnknownHostException, SocketException,
            InterruptedException {
        ByteBuf buf = Unpooled.wrappedBuffer(ipAddress);
        Future<List<String>> future = reverse(buf);
        buf.release();
        return future;
    }

    /**
     * Returns a {@link Future} which can be used to obtain a {@link List} of
     * domain names as {@link String}s when given their corresponding IP
     * address.
     *
     * @param ipAddress
     *            the ip address to perform a reverse lookup on
     * @throws UnknownHostException
     * @throws SocketException
     * @throws InterruptedException
     */
    public static Future<List<String>> reverse(ByteBuf ipAddress) throws UnknownHostException, SocketException,
            InterruptedException {
        int size = ipAddress.writerIndex() - ipAddress.readerIndex();
        StringBuilder domain = new StringBuilder();
        for (int i = size - 1; i > -1; i--) {
            domain.append(ipAddress.getUnsignedByte(i)).append(".");
        }
        return resolve(domain.append("in-addr.arpa").toString(), dnsServers.get(0), DnsEntry.TYPE_PTR);
    }

    /**
     * Removes an inactive channel after timing out. Internal use only.
     *
     * @param channel
     *            the channel to be removed
     */
    protected static void removeChannel(Channel channel) {
        synchronized (dnsServerChannels) {
            for (Iterator<Map.Entry<byte[], Channel>> iter = dnsServerChannels.entrySet().iterator(); iter.hasNext();) {
                Map.Entry<byte[], Channel> entry = iter.next();
                if (entry.getValue() == channel) {
                    if (channel.isOpen()) {
                        try {
                            channel.close().sync();
                        } catch (Exception e) {
                            if (logger.isErrorEnabled()) {
                                byte[] address = entry.getKey();
                                StringBuilder string = new StringBuilder();
                                for (int i = 0; i < address.length; i++) {
                                    string.append(address[i]).append(".");
                                }
                                logger.error("Could not close channel for address " + string.substring(0, string.length() - 1), e);
                            }
                        } finally {
                            channel.eventLoop().shutdownGracefully();
                        }
                    }
                    iter.remove();
                    break;
                }
            }
        }
    }

    private DnsExchangeFactory() {
    }

}