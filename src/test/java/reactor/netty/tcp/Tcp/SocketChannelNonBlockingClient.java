package reactor.netty.tcp.Tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import static reactor.netty.tcp.TcpClientTests.sendOrder2Equip;
import static reactor.netty.tcp.TcpClientTests.str2Byte;

/**
 * Created by lzw on 17-2-28.
 */
public class SocketChannelNonBlockingClient {
    private static Charset charset = Charset.forName("GBK");
    private static ByteBuffer receiveBuffer = ByteBuffer.allocate(10240);
    private static ByteBuffer sendBuffer = ByteBuffer.allocate(10240);
    private static SocketChannel socketChannel = null;
    private static Selector selector = null;
    private static String userName = "client2";//客户端名
    private static String targetName = "client1";//收件人名
    private volatile static int registCount = 0;//注册通知。总共发5次
    private volatile static int orderActionCount = 0;//发送订单制作通知，总共发5次
    /**
     * 0是未发送注册信息，1是已经发送了注册信息，2是已经注册
     * ，3是未发送订单制作通知，4是已经发送了订单制作通知，5是对方接收到了订单制作通知，5是制作完成，6是失败
     */
    private volatile static int popcornOrderStatus = 0;

    public static void main(String[] args) {
        try {
            socketChannel = SocketChannel.open();
            //连接到服务端
            SocketAddress socketAddress = new InetSocketAddress("localhost", 8888);
            selector = Selector.open();//实例化一个选择器
            socketChannel.configureBlocking(false);//设置为非阻塞
            //先监听一个连接事件
            socketChannel.register(selector, SelectionKey.OP_CONNECT);
            //连接
            socketChannel.connect(socketAddress);

            //发送
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    synchronized (sendBuffer) {
                        try {
                            send(socketChannel, new byte[]{(byte) 0x01});
                        } catch (Exception e) {
                            e.printStackTrace();
                            try {
                                socketChannel.close();
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                }
            }, 3000, 4000);
//            new Thread(() -> {
//                try {
//                    receiveFromUser();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }).start();

            talk();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private static void talk() {
        try {
            while (true) {
                selector.select();//阻塞直到连接事件
                Iterator<SelectionKey> readyKeys = selector.selectedKeys().iterator();
                while (readyKeys.hasNext()) {
                    SelectionKey key = readyKeys.next();
                    if (key.isConnectable()) {
                        //非阻塞的情况下可能没有连接完成，这里调用finishConnect阻塞至连接完成
                        socketChannel.finishConnect();
                        //连接完成以后，先发送自己的userName以便保存在服务端的客户端map里面
                        synchronized (sendBuffer) {
                            SocketChannel socketChannel1 = (SocketChannel) key.channel();
                            sendBuffer.clear();
                            sendBuffer.put(charset.encode(userName));

                            /**
                             * 0是未发送注册信息，1是已经发送了注册信息，2是已经注册
                             * ，3是已经发送了订单制作通知，4是制作完成，5是失败
                             */
                            switch (popcornOrderStatus) {
                                case 0:
                                    send(socketChannel, str2Byte());
                                    socketChannel.register(selector, SelectionKey.OP_READ);//仅监听一个读取事件
                                    popcornOrderStatus = 1;
                                    registCount++;
                                    break;
                                case 1:
                                    //检查是否收到了注册信息，
                                    if (true) {
                                        popcornOrderStatus = 2;
                                    } else {
                                        if (registCount >= 5) {
                                            socketChannel.close();
                                            return;
                                        }
                                        registCount++;
                                        send(socketChannel, str2Byte());
                                    }
                                    socketChannel.register(selector, SelectionKey.OP_READ);//仅监听一个读取事件
                                case 2:
                                    //发送订单制作通知
                                    send(socketChannel1, sendOrder2Equip(new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04}
                                            , "201807080938187791924059"));
                                    popcornOrderStatus = 3;
                                case 3:
                                    //检查是否收到了订单制作通知
//                                    if (true) {
//                                        popcornOrderStatus = 4;
//                                    } else {
//
//                                    }
                                    break;
                                case 4:
                                    //检查是否制作完成一次,判断是否再次制作，不需要则关闭，如果异常，也要关闭链接 todo
                                    socketChannel.close();
                                    break;
                                case 5:
                                    socketChannel.close();
                                    break;
                            }


                        }

                    } else if (key.isReadable()) {
                        //处理读事件
                        receive(key);
                    }
                    readyKeys.remove();
                }
            }
        } catch (ClosedChannelException e) {
            try {
                socketChannel.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    /**
     * 接收服务端的数据
     *
     * @param key
     */
    private static void receive(SelectionKey key) throws IOException {
        //获取服务端的channel
        SocketChannel channel = (SocketChannel) key.channel();
        //为写入缓冲器做准备position=0,limit=capacity
        receiveBuffer.clear();
        //从服务端的channel把数据读入缓冲器
        channel.read(receiveBuffer);
        //position=0,limit=有效下标最后一位
        receiveBuffer.flip();

        /**
         * 0是未发送注册信息，1是已经发送了注册信息，2是已经注册
         * ，3是未发送订单制作通知，4是已经发送了订单制作通知，5是对方接收到了订单制作通知，5是制作完成，6是失败
         */
        switch (popcornOrderStatus) {
            case 0:
                send(socketChannel, sendOrder2Equip(new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04}
                        , "201807080938187791924059"));
                popcornOrderStatus = 1;
                break;
            case 1:
                //判断是否有注册成功通知,如果有成功通知则设为状态2，如果没有则再次发送一次，总共试5次
                byte[] registRes = receiveBuffer.array();
                if (null == receiveBuffer || registRes.length < 1 || registRes[0] == (byte) 0x01) {
                    if (registCount >= 5) {
                        socketChannel.close();
                        break;
                    }
                    registCount++;
                    send(socketChannel, sendOrder2Equip(new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04}
                            , "201807080938187791924059"));
                    break;
                }
                popcornOrderStatus = 2;
            case 2:
                //发送订单制作通知

            case 3:
                socketChannel.register(selector, SelectionKey.OP_READ);//仅监听一个读取事件
            case 4:
                socketChannel.close();
                break;
        }
        //解码
        String msg = charset.decode(receiveBuffer).toString();
        //输出到控制台
        System.out.println(msg);
    }

    /**
     * 发送到服务端
     */
    private static void send(SocketChannel sendChannel, byte[] msg) throws IOException {
        sendBuffer.clear();
        sendBuffer.put(ByteBuffer.wrap(msg));
        if (sendBuffer.remaining() != 0) {
            synchronized (sendBuffer) {
                sendBuffer.flip();
                sendChannel.write(sendBuffer);
            }
        }
    }
}