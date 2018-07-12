package reactor.netty;
/**
 * created by shen on 2018/7/12
 */

/**
 * @since 2018/7/12
 * @author shen
 * @version 1.0.0
 */
public class HelloWorld {
    public native void sayHello();
    public static void main(String[] args) {
//        System.setProperty("java.library.path", "../../../");
        System.out.println(System.getProperty("java.library.path"));
        System.loadLibrary("NativeCode");
        HelloWorld nativeCode = new HelloWorld();
        nativeCode.sayHello();
    }
}
