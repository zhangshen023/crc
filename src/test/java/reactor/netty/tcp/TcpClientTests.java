/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.tcp;

import com.google.common.primitives.Bytes;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LineBasedFrameDecoder;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.SocketUtils;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import static reactor.netty.tcp.CRC8.IntToByte;
import static reactor.netty.tcp.TcpClientTests.FrameStatus.FRAME_32;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpClientTests {

    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    int echoServerPort;
    EchoServer echoServer;
    int abortServerPort;
    ConnectionAbortServer abortServer;
    int timeoutServerPort;
    ConnectionTimeoutServer timeoutServer;
    int heartbeatServerPort;
    HeartbeatServer heartbeatServer;

    @Before
    public void setup() throws Exception {
        echoServerPort = SocketUtils.findAvailableTcpPort();
        echoServer = new EchoServer(echoServerPort);
        threadPool.submit(echoServer);
        if (!echoServer.await(10, TimeUnit.SECONDS)) {
            throw new IOException("fail to start test server");
        }

        abortServerPort = SocketUtils.findAvailableTcpPort();
        abortServer = new ConnectionAbortServer(abortServerPort);
        threadPool.submit(abortServer);
        if (!abortServer.await(10, TimeUnit.SECONDS)) {
            throw new IOException("fail to start test server");
        }

        timeoutServerPort = SocketUtils.findAvailableTcpPort();
        timeoutServer = new ConnectionTimeoutServer(timeoutServerPort);
        threadPool.submit(timeoutServer);
        if (!timeoutServer.await(10, TimeUnit.SECONDS)) {
            throw new IOException("fail to start test server");
        }

        heartbeatServerPort = SocketUtils.findAvailableTcpPort();
        heartbeatServer = new HeartbeatServer(heartbeatServerPort);
        threadPool.submit(heartbeatServer);
        if (!heartbeatServer.await(10, TimeUnit.SECONDS)) {
            throw new IOException("fail to start test server");
        }
    }

    public static byte[] intToByteArray(int i) {
        byte[] result = new byte[4];
        //由高位到低位
        result[0] = (byte)((i >> 24) & 0xFF);
        result[1] = (byte)((i >> 16) & 0xFF);
        result[2] = (byte)((i >> 8) & 0xFF);
        result[3] = (byte)(i & 0xFF);
        return result;
    }

    @After
    public void cleanup() throws InterruptedException, IOException {
        echoServer.close();
        abortServer.close();
        timeoutServer.close();
        heartbeatServer.close();
        threadPool.shutdown();
        threadPool.awaitTermination(5, TimeUnit.SECONDS);
        Thread.sleep(500);
    }

    @Test
    public void disableSsl() {
        TcpClient secureClient = TcpClient.create()
                .secure();

        assertTrue(secureClient.isSecure());
        assertFalse(secureClient.noSSL().isSecure());
    }

    @Test
    public void testTcpClient() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        Connection client = TcpClient.create()
                .host("119.29.235.72")
                .port(9001)

                .handle((in, out) -> {
                    NettyOutbound nettyOutbound = out.sendByteArray(Flux.just(str2Byte()));
                    return nettyOutbound.neverComplete();
                })
                .wiretap()
                .connectNow();

        latch.await(5, TimeUnit.SECONDS);

        client.dispose();

//        assertThat("latch was counted down", latch.getCount(), is(0L));
    }

    public static void main(String[] args) {
        Mono.never().subscribe(System.out::println);
    }

    public static byte[] str2Byte() {
        byte[] bytes = new byte[20];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = 20;

        }

        byte[] reg = "100001".getBytes();
        for (int i = 0; i < reg.length; i++) {
            bytes[i] = reg[i];
        }

        return bytes;
    }

    @Test
    public void testTcpClientWithInetSocketAddress() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        TcpClient client =
                TcpClient.create().port(echoServerPort);

        Connection s = client.handle((in, out) -> {
            in.receive()
                    .subscribe(d -> latch.countDown());

            return out.sendString(Flux.just("Hello"))
                    .neverComplete();
        })
                .wiretap()
                .connectNow(Duration.ofSeconds(5));

        latch.await(5, TimeUnit.SECONDS);

        s.dispose();

        assertThat("latch was counted down", latch.getCount(), is(0L));
    }

    @Test
    public void tcpClientHandlesLineFeedData() throws InterruptedException {
        final int messages = 100;
        final CountDownLatch latch = new CountDownLatch(messages);
        final List<String> strings = new ArrayList<>();

        Connection client =
                TcpClient.create()
                        .host("localhost")
                        .port(echoServerPort)
                        .doOnConnected(c -> c.addHandlerLast("codec",
                                new LineBasedFrameDecoder(8 * 1024)))
                        .handle((in, out) ->
                                out.sendString(Flux.range(1, messages)
                                        .map(i -> "Hello World!" + i + "\n")
                                        .subscribeOn(Schedulers.parallel()))
                                        .then(in.receive()
                                                .asString()
                                                .take(100)
                                                .flatMapIterable(s -> Arrays.asList(s.split("\\n")))
                                                .doOnNext(s -> {
                                                    strings.add(s);
                                                    latch.countDown();
                                                }).then())
                        )
                        .wiretap()
                        .connectNow(Duration.ofSeconds(15));

        assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
                latch.await(15, TimeUnit.SECONDS));

        assertEquals(messages, strings.size());
        client.disposeNow();
    }

    @Test
    public void tcpClientHandlesLineFeedDataFixedPool() throws InterruptedException {
        Consumer<? super Connection> channelInit = c -> c
                .addHandler("codec",
                        new LineBasedFrameDecoder(8 * 1024));

//		ConnectionProvider p = ConnectionProvider.fixed
//				("tcpClientHandlesLineFeedDataFixedPool", 1);

        ConnectionProvider p = ConnectionProvider.newConnection();

        tcpClientHandlesLineFeedData(
                TcpClient.create(p)
                        .host("localhost")
                        .port(echoServerPort)
                        .doOnConnected(channelInit)
        );

    }

    @Test
    public void tcpClientHandlesLineFeedDataElasticPool() throws InterruptedException {
        Consumer<? super Connection> channelInit = c -> c
                .addHandler("codec",
                        new LineBasedFrameDecoder(8 * 1024));

        tcpClientHandlesLineFeedData(
                TcpClient.create(ConnectionProvider.elastic("tcpClientHandlesLineFeedDataElasticPool"))
                        .host("localhost")
                        .port(echoServerPort)
                        .doOnConnected(channelInit)
        );
    }

    private void tcpClientHandlesLineFeedData(TcpClient client) throws InterruptedException {
        final int messages = 100;
        final CountDownLatch latch = new CountDownLatch(messages);
        final List<String> strings = new ArrayList<>();

        Connection c = client.handle((in, out) ->
                out.sendString(Flux.range(1, messages)
                        .map(i -> "Hello World!" + i + "\n")
                        .subscribeOn(Schedulers.parallel()))
                        .then(in.receive()
                                .asString()
                                .take(100)
                                .flatMapIterable(s -> Arrays.asList(s.split("\\n")))
                                .doOnNext(s -> {
                                    strings.add(s);
                                    latch.countDown();
                                }).then())
        )
                .wiretap()
                .connectNow(Duration.ofSeconds(30));

        System.out.println("Connected");

        c.onDispose()
                .log()
                .block(Duration.ofSeconds(30));

        assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
                latch.await(15, TimeUnit.SECONDS));

        assertEquals(messages, strings.size());
    }

    @Test
    public void closingPromiseIsFulfilled() {
        TcpClient client =
                TcpClient.newConnection()
                        .host("localhost")
                        .port(abortServerPort);

        client.handle((in, out) -> Mono.empty())
                .wiretap()
                .connectNow()
                .disposeNow();
    }

    /*Check in details*/
    private void connectionWillRetryConnectionAttemptWhenItFails(TcpClient client)
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicLong totalDelay = new AtomicLong();

        client.handle((in, out) -> Mono.never())
                .wiretap()
                .connect()
                .retryWhen(errors -> errors.zipWith(Flux.range(1, 4), (a, b) -> b)
                        .flatMap(attempt -> {
                            switch (attempt) {
                                case 1:
                                    totalDelay.addAndGet(100);
                                    return Mono.delay(Duration
                                            .ofMillis(100));
                                case 2:
                                    totalDelay.addAndGet(500);
                                    return Mono.delay(Duration
                                            .ofMillis(500));
                                case 3:
                                    totalDelay.addAndGet(1000);
                                    return Mono.delay(Duration
                                            .ofSeconds(1));
                                default:
                                    latch.countDown();
                                    return Mono.<Long>empty();
                            }
                        }))
                .subscribe(System.out::println);

        latch.await(5, TimeUnit.SECONDS);
        assertTrue("latch was counted down:" + latch.getCount(), latch.getCount() == 0);
        assertThat("totalDelay was >1.6s", totalDelay.get(), greaterThanOrEqualTo(1600L));
    }

    /*Check in details*/
    @Test
    public void connectionWillRetryConnectionAttemptWhenItFailsElastic()
            throws InterruptedException {
        connectionWillRetryConnectionAttemptWhenItFails(
                TcpClient.create()
                        .host("localhost")
                        .port(abortServerPort + 3)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
    }

    //see https://github.com/reactor/reactor-netty/issues/289
    @Test
    public void connectionWillRetryConnectionAttemptWhenItFailsFixedChannelPool()
            throws InterruptedException {
        connectionWillRetryConnectionAttemptWhenItFails(
                TcpClient.create(ConnectionProvider.fixed("test", 1))
                        .host("localhost")
                        .port(abortServerPort + 3)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100));
    }

    @Test
    public void connectionWillAttemptToReconnectWhenItIsDropped()
            throws InterruptedException {
        final CountDownLatch connectionLatch = new CountDownLatch(1);
        final CountDownLatch reconnectionLatch = new CountDownLatch(1);

        try {
            TcpClient tcpClient =
                    TcpClient.newConnection()
                            .host("localhost")
                            .port(abortServerPort);

            Mono<? extends Connection> handler = tcpClient.handle((in, out) -> {
                System.out.println("Start");
                connectionLatch.countDown();
                in.receive()
                        .subscribe();
                return Flux.never();
            })
                    .wiretap()
                    .connect();

            handler.log()
                    .then(handler.doOnSuccess(s -> reconnectionLatch.countDown()))
                    .block(Duration.ofSeconds(30))
                    .onDispose();

            assertTrue("Initial connection is made", connectionLatch.await(5, TimeUnit.SECONDS));
            assertTrue("A reconnect attempt was made", reconnectionLatch.await(5, TimeUnit.SECONDS));
        } catch (AbortedException ise) {
            return;
        }
    }

    @Test
    public void testCancelSend() throws InterruptedException {
        final CountDownLatch connectionLatch = new CountDownLatch(3);

        TcpClient tcpClient =
                TcpClient.newConnection()
                        .host("119.29.235.72")
                        .port(9001);
        Connection c;

        c = tcpClient.handle(new BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>() {
            @Override
            public Publisher<Void> apply(NettyInbound i, NettyOutbound o) {

                o.sendByteArray(Flux.just(str2Byte())).then().subscribe().dispose();

                //发送订单
                byte[] equipNo = new byte[]{0x01, 0x02, 0x03, 0x04};
                o.sendByteArray(Flux.just(sendOrder2Equip(equipNo, null))).then().subscribe().dispose();

                o.sendObject(Mono.never()
                        .doOnCancel(connectionLatch::countDown)
                        .log("uno"))
                        .then()
                        .subscribe()
                        .dispose();

                Schedulers.parallel()
                        .schedule(() -> o.sendObject(Mono.never()
                                .doOnCancel(connectionLatch::countDown)
                                .log("dos"))
                                .then()
                                .subscribe()
                                .dispose());

                o.sendObject(Mono.never()
                        .doOnCancel(connectionLatch::countDown)
                        .log("tres"))
                        .then()
                        .subscribe()
                        .dispose();

                return Mono.never();
            }
        })
                .connectNow();

        assertTrue("Cancel not propagated", connectionLatch.await(30, TimeUnit.SECONDS));
        c.dispose();
    }

    /**
     * @param equipNo 4个字节
     * @return
     */
    public static byte[] sendOrder2Equip(byte[] equipNo, String orderNo) {
        List<Byte> msg = new ArrayList<>();
        List<Byte> realMsg = new ArrayList<>();
        //发送帧头1
        msg.add((byte) 0xAA);
        //发送帧头2
        msg.add((byte) 0x55);
        //版本号
        msg.add((byte) 0x01);
        realMsg.add((byte) 0x01);
        //数据长度
        msg.add((byte) 0x19);
        realMsg.add((byte) 0x19);
        //命令
        msg.add(FRAME_32.getOrder());
        realMsg.add(FRAME_32.getOrder());
        //设备编号
        msg.addAll(Bytes.asList(equipNo));
        realMsg.addAll(Bytes.asList(equipNo));
        //订单选项
        msg.add((byte) 0x01);
        realMsg.add((byte) 0x01);
        byte[] orderBytes = "201807080938187791924059".getBytes(Charset.forName("utf-8"));
        msg.addAll(Bytes.asList(orderBytes));
        realMsg.addAll(Bytes.asList(orderBytes));
        //CRC_H CRC_L
        CRC16M crc16M =  new CRC16M();
        crc16M.update(Bytes.toArray(realMsg),Bytes.toArray(realMsg).length);
        realMsg.addAll(Bytes.asList(intToByteArray(crc16M.getValue())));
        //帧尾
        msg.add((byte) (0xFE));
        return Bytes.toArray(realMsg);
    }

    public enum EquipStatus {
        PROD,
        MAINT,
        CLEAN,
        CRASH
    }

    public enum FrameStatus {


        FRAME_30((byte) 0x30),
        FRAME_31((byte) 0x31),
        FRAME_32((byte) 0x32),
        FRAME_34((byte) 0x34),
        FRAME_DEFAULT((byte) 0x35),
        FRAME_33((byte) 0x33);


        private byte order;

        public static FrameStatus getFrameStatus(byte order) {
            return Arrays.stream(FrameStatus.values())
                    .filter(frameStatus -> frameStatus.getOrder() == order)
                    .findFirst()
                    .orElse(FRAME_DEFAULT);
        }

        FrameStatus(byte order) {
            this.order = order;
        }

        public byte getOrder() {
            return order;
        }

    }


    @Ignore
    public void consumerSpecAssignsEventHandlers()
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch close = new CountDownLatch(1);
        final AtomicLong totalDelay = new AtomicLong();
        final long start = System.currentTimeMillis();

        TcpClient client =
                TcpClient.create()
                        .host("localhost")
                        .port(timeoutServerPort);

        Connection s = client.handle((in, out) -> {
            in.withConnection(c -> c.onDispose(close::countDown));

            out.withConnection(c -> c.onWriteIdle(500, () -> {
                totalDelay.addAndGet(System.currentTimeMillis() - start);
                latch.countDown();
            }));

            return Mono.delay(Duration.ofSeconds(3))
                    .then()
                    .log();
        })
                .wiretap()
                .connectNow();

        assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));
        assertTrue("close was counted down", close.await(30, TimeUnit.SECONDS));
        assertThat("totalDelay was >500ms", totalDelay.get(), greaterThanOrEqualTo(500L));
        s.dispose();
    }

    @Test
    @Ignore
    public void readIdleDoesNotFireWhileDataIsBeingRead()
            throws InterruptedException, IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        long start = System.currentTimeMillis();

        TcpClient client = TcpClient.create()
                .port(heartbeatServerPort);

        Connection s = client.handle((in, out) -> {
            in.withConnection(c -> c.onReadIdle(500, latch::countDown));
            return Flux.never();
        })
                .wiretap()
                .connectNow();

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        heartbeatServer.close();

        long duration = System.currentTimeMillis() - start;

        assertThat(duration, is(greaterThanOrEqualTo(500L)));
        s.dispose();
    }

    @Test
    public void writeIdleDoesNotFireWhileDataIsBeingSent()
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        long start = System.currentTimeMillis();

        Connection client = TcpClient.create()
                .host("localhost")
                .port(echoServerPort)
                .handle((in, out) -> {
                    System.out.println("hello");
                    out.withConnection(c -> c.onWriteIdle(500, latch::countDown));

                    List<Publisher<Void>> allWrites =
                            new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        allWrites.add(out.sendString(Flux.just("a")
                                .delayElements(Duration.ofMillis(750))));
                    }
                    return Flux.merge(allWrites);
                })
                .wiretap()
                .connectNow();

        System.out.println("Started");

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        long duration = System.currentTimeMillis() - start;

        assertThat(duration, is(greaterThanOrEqualTo(500L)));
        client.dispose();
    }

    @Test
    public void nettyNetChannelAcceptsNettyChannelHandlers() throws InterruptedException {
        HttpClient client = HttpClient.create()
                .wiretap();

        final CountDownLatch latch = new CountDownLatch(1);
        System.out.println(client.get()
                .uri("http://www.google.com/?q=test%20d%20dq")
                .responseContent()
                .asString()
                .collectList()
                .doOnSuccess(v -> latch.countDown())
                .block(Duration.ofSeconds(30)));

        assertTrue("Latch didn't time out", latch.await(15, TimeUnit.SECONDS));
    }

    @Test
    public void gettingOptionsDuplicates() {
        TcpClient client = TcpClient.create().host("foo").port(123);
        Assertions.assertThat(client.configure())
                .isNotSameAs(TcpClient.DEFAULT_BOOTSTRAP)
                .isNotSameAs(client.configure());
    }

    public static final class EchoServer
            extends CountDownLatch
            implements Runnable {

        private final int port;
        private final ServerSocketChannel server;
        private volatile Thread thread;

        public EchoServer(int port) {
            super(1);
            this.port = port;
            try {
                server = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            try {
                server.configureBlocking(true);
                server.socket()
                        .bind(new InetSocketAddress(port));
                countDown();
                thread = Thread.currentThread();
                while (true) {
                    SocketChannel ch = server.accept();

                    ByteBuffer buffer = ByteBuffer.allocate(8192);
                    while (true) {
                        int read = ch.read(buffer);
                        if (read > 0) {
                            buffer.flip();
                        }

                        int written = ch.write(buffer);
                        if (written < 0) {
                            throw new IOException("Cannot write to client");
                        }
                        buffer.rewind();
                    }
                }
            } catch (IOException e) {
                // Server closed
            }
        }

        public void close() throws IOException {
            Thread thread = this.thread;
            if (thread != null) {
                thread.interrupt();
            }
            ServerSocketChannel server = this.server;
            if (server != null) {
                server.close();
            }
        }
    }

    private static final class ConnectionAbortServer
            extends CountDownLatch
            implements Runnable {

        final int port;
        private final ServerSocketChannel server;

        private ConnectionAbortServer(int port) {
            super(1);
            this.port = port;
            try {
                server = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            try {
                server.configureBlocking(true);
                server.socket()
                        .bind(new InetSocketAddress(port));
                countDown();
                while (true) {
                    SocketChannel ch = server.accept();
                    System.out.println("ABORTING");
                    ch.close();
                }
            } catch (Exception e) {
                // Server closed
            }
        }

        public void close() throws IOException {
            ServerSocketChannel server = this.server;
            if (server != null) {
                server.close();
            }
        }
    }

    private static final class ConnectionTimeoutServer
            extends CountDownLatch
            implements Runnable {

        final int port;
        private final ServerSocketChannel server;

        private ConnectionTimeoutServer(int port) {
            super(1);
            this.port = port;
            try {
                server = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            try {
                server.configureBlocking(true);
                server.socket()
                        .bind(new InetSocketAddress(port));
                countDown();
                while (true) {
                    SocketChannel ch = server.accept();
                    ByteBuffer buff = ByteBuffer.allocate(1);
                    ch.read(buff);
                }
            } catch (IOException e) {
            }
        }

        public void close() throws IOException {
            ServerSocketChannel server = this.server;
            if (server != null) {
                server.close();
            }
        }
    }

    private static final class HeartbeatServer extends CountDownLatch
            implements Runnable {

        final int port;
        private final ServerSocketChannel server;

        private HeartbeatServer(int port) {
            super(1);
            this.port = port;
            try {
                server = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            try {
                server.configureBlocking(true);
                server.socket()
                        .bind(new InetSocketAddress(port));
                countDown();
                while (true) {
                    SocketChannel ch = server.accept();
                    while (server.isOpen()) {
                        ByteBuffer out = ByteBuffer.allocate(1);
                        out.put((byte) '\n');
                        out.flip();
                        ch.write(out);
                        Thread.sleep(100);
                    }
                }
            } catch (IOException e) {
                // Server closed
            } catch (InterruptedException ie) {

            }
        }

        public void close() throws IOException {
            ServerSocketChannel server = this.server;
            if (server != null) {
                server.close();
            }
        }
    }

}