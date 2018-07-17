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
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import static reactor.netty.Test.intToByteArray;
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
    public void testCancelSend() throws InterruptedException {
        final CountDownLatch connectionLatch = new CountDownLatch(3);

        TcpClient tcpClient =
                TcpClient.newConnection()
                        .host("119.29.235.72")
                        .port(9001);
        Connection c;

        c = tcpClient.handle((i, o) -> {

            o.sendByteArray(Flux.just(str2Byte()))
                    .then()
                    .subscribe(new Consumer<Void>() {
                        @Override
                        public void accept(Void aVoid) {
                            //发送订单
                            o.sendByteArray(Flux.just(sendOrder2Equip(new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04}
                                    , "201807080938187791924059")))
                                    .then()
                                    .subscribe()
                                    .dispose();
                        }
                    })
                    .dispose();


            return Mono.never();
        }).connectNow();

        Thread.sleep(4000);
        c.dispose();
    }

    /**
     * @param equipNo 4个字节 new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04}
     * @param orderNo 订单号 "201807080938187791924059"
     * @return 发送订单到设备服务器
     */
    public static byte[] sendOrder2Equip(byte[] equipNo, String orderNo) {
        byte[] msg = new byte[37];
        //发送帧头1
        int pos = 0;
        msg[pos++] = (byte) 0xAA;
        //发送帧头2
        msg[pos++] = (byte) 0x55;
        //版本号
        msg[pos++] = (byte) 0x01;
        //数据长度
        msg[pos++] = (byte) 0x19;
        //命令
        msg[pos++] = FRAME_32.getOrder();
        //设备编号
        int tmpPos = 0;
        while (tmpPos < 4) {
            msg[pos++] = equipNo[tmpPos++];
        }
        //订单选项
        msg[pos++] = (byte) 0x01;
        byte[] orderBytes = "201807080938187791924059".getBytes(Charset.forName("utf-8"));
        tmpPos = 0;
        while (tmpPos < 24) {
            msg[pos++] = orderBytes[tmpPos++];
        }
        //CRC_H CRC_L
        tmpPos = 2;
        byte[] tempByteArray2Crc = Arrays.copyOfRange(msg, 2, 34);
        System.out.println("test len:" + tempByteArray2Crc.length);
        byte[] crcValue = intToByteArray(CRC8.getcrc(tempByteArray2Crc));
        System.out.println(CRC8.getcrc(tempByteArray2Crc));
//        System.out.println("二进制校验值:", Integer.toBinaryString(CRC8.getcrc(tempByteArray2Crc));
        msg[pos++] = crcValue[3];
        msg[pos++] = crcValue[2];
        System.out.println("二进制校验值：" + bytesToHexFun2(new byte[]{crcValue[2]}) + "," + bytesToHexFun2(new byte[]{crcValue[3]}));
        //while (tmpPos < 4) {
        //    msg[pos++] = crcValue[tmpPos++];
        // }
        //帧尾
        msg[pos++] = ((byte) (0xFE));
        return msg;
    }

    /**
     * 方法二：
     * byte[] to hex string
     *
     * @param bytes
     * @return
     */
    public static String bytesToHexFun2(byte[] bytes) {
        char[] buf = new char[bytes.length * 2];
        int index = 0;
        for (byte b : bytes) { // 利用位运算进行转换，可以看作方法一的变种
            buf[index++] = HEX_CHAR[b >>> 4 & 0xf];
            buf[index++] = HEX_CHAR[b & 0xf];
        }

        return new String(buf);
    }

    private static final char[] HEX_CHAR = {'0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

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
                        System.out.println("请求:" + buffer.toString());
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
