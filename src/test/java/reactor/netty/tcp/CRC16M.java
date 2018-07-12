package reactor.netty.tcp;


import reactor.netty.Test;

/**
 * 循环冗余检验：CRC-16-CCITT查表法
 */
public final class CRC16M {

    public static void main(String[] args) {
        int result = Test.GetCrc16(new byte[]{1, 2, 3});
        System.out.println(Integer.toHexString(result));
    }


}
