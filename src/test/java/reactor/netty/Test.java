package reactor.netty;

import java.util.ArrayList;
import java.util.List;

public class Test {

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>();
        list.add(1);
        list.add(0, 2);
        for (Integer integer : list) {

            System.out.println(integer);
        }
    }
}
