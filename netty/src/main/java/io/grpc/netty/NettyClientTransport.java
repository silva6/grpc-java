/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.netty;

import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Ticker;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FailingClientStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.KeepAliveManager.ClientKeepAlivePinger;
import io.grpc.internal.LogId;
import io.grpc.internal.StatsTraceContext;
import io.grpc.netty.ProtocolNegotiator.Handler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.StreamBufferingEncoder.Http2ChannelClosedException;
import io.netty.util.AsciiString;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A Netty-based {@link ConnectionClientTransport} implementation.
 */
class NettyClientTransport implements ConnectionClientTransport {
  private static final Logger logger = Logger.getLogger(NettyClientTransport.class.getName());

  private final LogId logId = LogId.allocate(getClass().getName());
  private final Map<ChannelOption<?>, ?> channelOptions;
  private final SocketAddress address;
  private final Class<? extends Channel> channelType;
  private final EventLoopGroup group;
  private final ProtocolNegotiator negotiator;
  private final ChannelHandler initHandler;
  private final AsciiString authority;
  private final AsciiString userAgent;
  private final int flowControlWindow;
  private final int maxMessageSize;
  private final int maxHeaderListSize;
  private KeepAliveManager keepAliveManager;
  private final long keepAliveTimeNanos;
  private final long keepAliveTimeoutNanos;
  private final boolean keepAliveWithoutCalls;
  private final Runnable tooManyPingsRunnable;

  private AsciiString negotiatorScheme;
  private NettyClientHandler handler;
  // We should not send on the channel until negotiation completes. This is a hard requirement
  // by SslHandler but is appropriate for HTTP/1.1 Upgrade as well.
  private Channel channel;
  /** If {@link #start} has been called, non-{@code null} if channel is {@code null}. */
  private Status statusExplainingWhyTheChannelIsNull;
  /** Since not thread-safe, may only be used from event loop. */
  private ClientTransportLifecycleManager lifecycleManager;
  private final AtomicBoolean notifyClosed = new AtomicBoolean();

  NettyClientTransport(
      SocketAddress address,
      Class<? extends Channel> channelType,
      Map<ChannelOption<?>, ?> channelOptions,
      EventLoopGroup group,
      ProtocolNegotiator negotiator,
      @Nullable ChannelHandler initHandler,
      int flowControlWindow,
      int maxMessageSize,
      int maxHeaderListSize,
      long keepAliveTimeNanos,
      long keepAliveTimeoutNanos,
      boolean keepAliveWithoutCalls,
      String authority,
      @Nullable String userAgent,
      Runnable tooManyPingsRunnable) {
    this.negotiator = Preconditions.checkNotNull(negotiator, "negotiator");
    this.initHandler = initHandler;
    this.address = Preconditions.checkNotNull(address, "address");
    this.group = Preconditions.checkNotNull(group, "group");
    this.channelType = Preconditions.checkNotNull(channelType, "channelType");
    this.channelOptions = Preconditions.checkNotNull(channelOptions, "channelOptions");
    this.flowControlWindow = flowControlWindow;
    this.maxMessageSize = maxMessageSize;
    this.maxHeaderListSize = maxHeaderListSize;
    this.keepAliveTimeNanos = keepAliveTimeNanos;
    this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
    this.keepAliveWithoutCalls = keepAliveWithoutCalls;
    this.authority = new AsciiString(authority);
    this.userAgent = new AsciiString(GrpcUtil.getGrpcUserAgent("netty", userAgent));
    this.tooManyPingsRunnable =
        Preconditions.checkNotNull(tooManyPingsRunnable, "tooManyPingsRunnable");
  }

  @Override
  public void ping(final PingCallback callback, final Executor executor) {
    if (channel == null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          callback.onFailure(statusExplainingWhyTheChannelIsNull.asException());
        }
      });
      return;
    }
    // The promise and listener always succeed in NettyClientHandler. So this listener handles the
    // error case, when the channel is closed and the NettyClientHandler no longer in the pipeline.
    ChannelFutureListener failureListener = new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          Status s = statusFromFailedFuture(future);
          Http2Ping.notifyFailed(callback, executor, s.asException());
        }
      }
    };
    // Write the command requesting the ping
    handler.getWriteQueue().enqueue(new SendPingCommand(callback, executor), true)
        .addListener(failureListener);
  }

  @Override
  public ClientStream newStream(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
    Preconditions.checkNotNull(method, "method");
    Preconditions.checkNotNull(headers, "headers");
    if (channel == null) {
      return new FailingClientStream(statusExplainingWhyTheChannelIsNull);
    }
    StatsTraceContext statsTraceCtx = StatsTraceContext.newClientContext(callOptions, headers);
    return new NettyClientStream(
        new NettyClientStream.TransportState(handler, channel.eventLoop(), maxMessageSize,
            statsTraceCtx) {
          @Override
          protected Status statusFromFailedFuture(ChannelFuture f) {
            return NettyClientTransport.this.statusFromFailedFuture(f);
          }
        },
        method, headers, channel, authority, negotiatorScheme, userAgent, statsTraceCtx);
  }
  
  private void lifecycleManagerNotifyTerminated(String reason, Throwable t) {
    lifecycleManagerNotifyTerminated(reason, Utils.statusFromThrowable(t));
  }

  private void lifecycleManagerNotifyTerminated(String detail, Status s) {
    if (!notifyClosed.compareAndSet(false, true)) {
      logger.log(
          Level.FINE, detail + "ignoring additional lifecycle errors", s.asRuntimeException());
      return;
    }
    if (detail != null) {
      s = s.augmentDescription(detail);
    }
    lifecycleManager.notifyTerminated(s);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Runnable start(Listener transportListener) {
    lifecycleManager = new ClientTransportLifecycleManager(
        Preconditions.checkNotNull(transportListener, "listener"));
    EventLoop eventLoop = group.next();
    if (keepAliveTimeNanos != KEEPALIVE_TIME_NANOS_DISABLED) {
      keepAliveManager = new KeepAliveManager(
          new ClientKeepAlivePinger(this), eventLoop, keepAliveTimeNanos, keepAliveTimeoutNanos,
          keepAliveWithoutCalls);
    }

    handler = NettyClientHandler.newHandler(lifecycleManager, keepAliveManager, flowControlWindow,
        maxHeaderListSize, Ticker.systemTicker(), tooManyPingsRunnable);
    NettyHandlerSettings.setAutoWindow(handler);

    Bootstrap b = new Bootstrap();
    b.group(eventLoop);
    b.channel(channelType);
    if (NioSocketChannel.class.isAssignableFrom(channelType)) {
      b.option(SO_KEEPALIVE, true);
    }
    for (Map.Entry<ChannelOption<?>, ?> entry : channelOptions.entrySet()) {
      // Every entry in the map is obtained from
      // NettyChannelBuilder#withOption(ChannelOption<T> option, T value)
      // so it is safe to pass the key-value pair to b.option().
      b.option((ChannelOption<Object>) entry.getKey(), entry.getValue());
    }

    final class LifecycleChannelFutureListener implements ChannelFutureListener {
      private final String reason;

      LifecycleChannelFutureListener(String reason) {
        this.reason = reason;
      }

      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          lifecycleManagerNotifyTerminated(reason, future.cause());
        }
      }
    }

    final Handler negotiationHandler = negotiator.newHandler(handler);
    negotiatorScheme = negotiationHandler.scheme();

    b.handler(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(Channel ch) throws Exception {
        if (initHandler != null) {
          ch.pipeline().addFirst(initHandler);
        }

        ch.pipeline().addLast(negotiationHandler);

        // This write will have no effect, yet it will only complete once the negotiationHandler
        // flushes any pending writes.
        ch.writeAndFlush(NettyClientHandler.NOOP_MESSAGE)
            .addListener(new LifecycleChannelFutureListener("noop write"));
      }
    });

    ChannelFuture regFuture = b.register();
    regFuture.addListener(new LifecycleChannelFutureListener("register"));

    channel = regFuture.channel();
    if (channel == null) {
      // Initialization has failed badly. All new streams should be made to fail.
      Throwable t = regFuture.cause();
      if (t == null) {
        t = new IllegalStateException("Channel is null, but future doesn't have a cause");
      }
      statusExplainingWhyTheChannelIsNull = Utils.statusFromThrowable(t);
      // Use a Runnable since lifecycleManager calls transportListener
      return new Runnable() {
        @Override
        public void run() {
          // NOTICE: we not are calling lifecycleManager from the event loop. But there isn't really
          // an event loop in this case, so nothing should be accessing the lifecycleManager. We
          // could use GlobalEventExecutor (which is what regFuture would use for notifying
          // listeners in this case), but avoiding on-demand thread creation in an error case seems
          // a good idea and is probably clearer threading.
          lifecycleManagerNotifyTerminated(null, statusExplainingWhyTheChannelIsNull);
        }
      };
    }
    // Start the write queue as soon as the channel is constructed
    handler.startWriteQueue(channel);

    // Start the connection operation to the server.
    channel.connect(address).addListener(new LifecycleChannelFutureListener("connect"));

    if (keepAliveManager != null) {
      keepAliveManager.onTransportStarted();
    }

    return null;
  }

  @Override
  public void shutdown(Status reason) {
    // start() could have failed
    if (channel == null) {
      return;
    }
    // Notifying of termination is automatically done when the channel closes.
    if (channel.isOpen()) {
      handler.getWriteQueue().enqueue(new GracefulCloseCommand(reason), true);
    }
  }

  @Override
  public void shutdownNow(final Status reason) {
    // Notifying of termination is automatically done when the channel closes.
    if (channel != null && channel.isOpen()) {
      handler.getWriteQueue().enqueue(new Runnable() {
        @Override
        public void run() {
          lifecycleManager.notifyShutdown(reason);
          // Call close() directly since negotiation may not have completed, such that a write would
          // be queued.
          channel.close();
          channel.write(new ForcefulCloseCommand(reason));
        }
      }, true);
    }
  }

  @Override
  public String toString() {
    return getLogId() + "(" + address + ")";
  }

  @Override
  public LogId getLogId() {
    return logId;
  }

  @Override
  public Attributes getAttributes() {
    // TODO(zhangkun83): fill channel security attributes
    return Attributes.EMPTY;
  }

  @VisibleForTesting
  Channel channel() {
    return channel;
  }

  @VisibleForTesting
  KeepAliveManager keepAliveManager() {
    return keepAliveManager;
  }

  /**
   * Convert ChannelFuture.cause() to a Status, taking into account that all handlers are removed
   * from the pipeline when the channel is closed. Since handlers are removed, you may get an
   * unhelpful exception like ClosedChannelException.
   *
   * <p>This method must only be called on the event loop.
   */
  private Status statusFromFailedFuture(ChannelFuture f) {
    Throwable t = f.cause();
    if (t instanceof ClosedChannelException
        // Exception thrown by the StreamBufferingEncoder if the channel is closed while there
        // are still streams buffered. This exception is not helpful. Replace it by the real
        // cause of the shutdown (if available).
        || t instanceof Http2ChannelClosedException) {
      Status shutdownStatus = lifecycleManager.getShutdownStatus();
      if (shutdownStatus == null) {
        return Status.UNKNOWN.withDescription("Channel closed but for unknown reason");
      }
      return shutdownStatus;
    }
    return Utils.statusFromThrowable(t);
  }
}
