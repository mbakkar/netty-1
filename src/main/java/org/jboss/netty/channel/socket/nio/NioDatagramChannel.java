/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.channel.socket.nio;

import static org.jboss.netty.channel.Channels.fireChannelOpen;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelSink;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.DatagramChannelConfig;
import org.jboss.netty.util.internal.DetectionUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides an NIO based {@link org.jboss.netty.channel.socket.DatagramChannel}.
 */
public final class NioDatagramChannel extends AbstractNioChannel<DatagramChannel>
                                implements org.jboss.netty.channel.socket.DatagramChannel {

    /**
     * The supported ProtocolFamily by UDP
     *
     */
    public enum ProtocolFamily {
        INET,
        INET6
    }
    
    /**
     * The {@link DatagramChannelConfig}.
     */
    private final NioDatagramChannelConfig config;
    private Map<InetAddress, List<MembershipKey>> memberships;
    
    /**
     * Use {@link #NioDatagramChannel(ChannelFactory, ChannelPipeline, ChannelSink, NioDatagramWorker, ProtocolFamily)}
     */
    @Deprecated
    NioDatagramChannel(final ChannelFactory factory,
            final ChannelPipeline pipeline, final ChannelSink sink,
            final NioDatagramWorker worker) {
        this(factory, pipeline, sink, worker, null);
    }
    
    NioDatagramChannel(final ChannelFactory factory,
            final ChannelPipeline pipeline, final ChannelSink sink,
            final NioDatagramWorker worker, ProtocolFamily family) {
        super(null, factory, pipeline, sink, worker, openNonBlockingChannel(family));
        config = new DefaultNioDatagramChannelConfig(channel);
        
        fireChannelOpen(this);

    }

    private static DatagramChannel openNonBlockingChannel(ProtocolFamily family) {
        try {
            final DatagramChannel channel;
            
            // check if we are on java 7 or if the family was not specified
            if (DetectionUtil.javaVersion() < 7 || family == null) {
                channel = DatagramChannel.open();
            } else {
                // This block only works on java7++, but we checked before if we have it
                switch (family) {
                case INET:
                    channel = DatagramChannel.open(java.net.StandardProtocolFamily.INET);
                    break;

                case INET6:
                    channel = DatagramChannel.open(java.net.StandardProtocolFamily.INET6);
                    break;

                default:
                    throw new IllegalArgumentException();
                }
            }
            
            channel.configureBlocking(false);
            return channel;
        } catch (final IOException e) {
            throw new ChannelException("Failed to open a DatagramChannel.", e);
        }
    }



    @Override
    public NioDatagramWorker getWorker() {
        return (NioDatagramWorker) super.getWorker();
    }
    
    public boolean isBound() {
        return isOpen() && channel.socket().isBound();
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    protected boolean setClosed() {
        return super.setClosed();
    }

    public NioDatagramChannelConfig getConfig() {
        return config;
    }

    DatagramChannel getDatagramChannel() {
        return channel;
    }


    
    public ChannelFuture joinGroup(InetAddress multicastAddress) {
       try {
            return joinGroup(multicastAddress, NetworkInterface.getByInetAddress(getLocalAddress().getAddress()), null);
        } catch (SocketException e) {
            return Channels.failedFuture(this, e);
        }
    }

    
    public ChannelFuture joinGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface) {
        return joinGroup(multicastAddress.getAddress(), networkInterface, null);
    }

    /**
     * Joins the specified multicast group at the specified interface using the specified source.
     */
    public ChannelFuture joinGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        if (DetectionUtil.javaVersion() < 7) {
            throw new UnsupportedOperationException();
        } else {
            if (multicastAddress == null) {
                throw new NullPointerException("multicastAddress");
            }
            
            if (networkInterface == null) {
                throw new NullPointerException("networkInterface");
            }
            
            try {
                MembershipKey key;
                if (source == null) {
                    key = channel.join(multicastAddress, networkInterface);
                } else {
                    key = channel.join(multicastAddress, networkInterface, source);
                }

                synchronized (this) {
                    if (memberships == null) {
                        memberships = new HashMap<InetAddress, List<MembershipKey>>();
                       
                    }
                    List<MembershipKey> keys = memberships.get(multicastAddress);
                    if (keys == null) {
                        keys = new ArrayList<MembershipKey>();
                        memberships.put(multicastAddress, keys);
                    }
                    keys.add(key);
                }
            } catch (Throwable e) {
                return Channels.failedFuture(this, e);
            }
        }
        return Channels.succeededFuture(this);
    }
    
    public ChannelFuture leaveGroup(InetAddress multicastAddress) {
        try {
            return leaveGroup(multicastAddress, NetworkInterface.getByInetAddress(getLocalAddress().getAddress()), null);
        } catch (SocketException e) {
            return Channels.failedFuture(this, e);
        }
        
    }

    public ChannelFuture leaveGroup(InetSocketAddress multicastAddress,
            NetworkInterface networkInterface) {
        return leaveGroup(multicastAddress.getAddress(), networkInterface, null);
    }

    /**
     * Leave the specified multicast group at the specified interface using the specified source.
     */
    public ChannelFuture leaveGroup(InetAddress multicastAddress,
            NetworkInterface networkInterface, InetAddress source) {
        if (DetectionUtil.javaVersion() < 7) {
            throw new UnsupportedOperationException();
        } else {
            if (multicastAddress == null) {
                throw new NullPointerException("multicastAddress");
            }
            
            if (networkInterface == null) {
                throw new NullPointerException("networkInterface");
            }
            
            synchronized (this) {
                if (memberships != null) {
                    List<MembershipKey> keys = memberships.get(multicastAddress);
                    if (keys != null) {
                        Iterator<MembershipKey> keyIt = keys.iterator();
                        
                        while (keyIt.hasNext()) {
                            MembershipKey key = keyIt.next();
                            if (networkInterface.equals(key.networkInterface())) {
                               if (source == null && key.sourceAddress() == null || (source != null && source.equals(key.sourceAddress()))) {
                                   key.drop();
                                   keyIt.remove();
                               }
                               
                            }
                        }
                        if (keys.isEmpty()) {
                            memberships.remove(multicastAddress);
                        }
                    }
                }
            }
            return Channels.succeededFuture(this);
        }
    }
    
    /**
     * Block the given sourceToBlock address for the given multicastAddress on the given networkInterface
     *
     */
    public ChannelFuture block(InetAddress multicastAddress,
            NetworkInterface networkInterface, InetAddress sourceToBlock) {
        if (DetectionUtil.javaVersion() < 7) {
            throw new UnsupportedOperationException();
        } else {
            if (multicastAddress == null) {
                throw new NullPointerException("multicastAddress");
            }
            if (sourceToBlock == null) {
                throw new NullPointerException("sourceToBlock");
            }
            
            if (networkInterface == null) {
                throw new NullPointerException("networkInterface");
            }
            synchronized (this) {
                if (memberships != null) {
                    List<MembershipKey> keys = memberships.get(multicastAddress);
                    for (MembershipKey key: keys) {
                        if (networkInterface.equals(key.networkInterface())) {
                            try {
                                key.block(sourceToBlock);
                            } catch (IOException e) {
                                return Channels.failedFuture(this, e);
                            }
                        }
                    }
                }
            }
            return Channels.succeededFuture(this);
            
            
        }
    }
    
    /**
* Block the given sourceToBlock address for the given multicastAddress
*
*/
    public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock) {
        try {
            block(multicastAddress, NetworkInterface.getByInetAddress(getLocalAddress().getAddress()), sourceToBlock);
        } catch (SocketException e) {
            return Channels.failedFuture(this, e);
        }
        return Channels.succeededFuture(this);

    }
    @Override
    InetSocketAddress getLocalSocketAddress() throws Exception {
        return (InetSocketAddress) channel.socket().getLocalSocketAddress();
    }

    @Override
    InetSocketAddress getRemoteSocketAddress() throws Exception {
        return (InetSocketAddress) channel.socket().getRemoteSocketAddress();
    }

    @Override
    public ChannelFuture write(Object message, SocketAddress remoteAddress) {
        if (remoteAddress == null || remoteAddress.equals(getRemoteAddress())) {
            return super.write(message, null);
        } else {
            return super.write(message, remoteAddress);
        }

    }
}