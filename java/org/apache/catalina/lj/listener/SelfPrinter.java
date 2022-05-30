package org.apache.catalina.lj.listener;

import org.apache.catalina.ContainerEvent;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleState;

import java.util.Arrays;

import static java.util.stream.Collectors.joining;

public class SelfPrinter {
    static {
        System.setProperty("tomcat.self.print", "false");
    }

    public static void core(Object o, String method) {
        System.out.println("core =>[" + o.getClass().getSimpleName() + "] 中的方法：" + method + "()");
    }

    public static void pushEle(Object o){
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("[ "+o.getClass().getSimpleName()+" ]即将入栈");
        }
    }

    public static void popEle(Object o){
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("[ "+o.getClass().getSimpleName()+" ]即将出栈");
        }
    }

    public static void reflectSetProperty(Object o, String name, String value) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("使用反射为:" + o.getClass().getSimpleName() + "设置属性,设置名称:[ " + name + " ],属性值:[ " + value+" ]");
        }
    }

    public static void invoke(Object o, String mn) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("开始 => 调用 [" + o.getClass().getSimpleName() + "] 中的方法：" + mn + "()");
    }

    public static void invoke(Object o, String mn, Object... params) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("   => 调用 [" + o.getClass().getSimpleName() + "] 中的方法：" + mn + "()");
            if (params.length > 0) {
                System.out.println("参数:" + Arrays.stream(params).map(Object::toString).collect(joining(",", "[", "]")));
            }
        }
    }

    public static void end(Object o, String mn) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("结束 => 调用 [" + o.getClass().getSimpleName() + "] 中的方法：" + mn + "()");
    }

    public static void listenerInvoke(Object o, Object listener, LifecycleEvent event) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("[" + o.getClass().getSimpleName() + "] 中的监听器:" + listener.getClass().getSimpleName() + ",触发了事件：" + event.getType());
    }

    public static void trigger(Object o, Object listener, ContainerEvent event) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("[" + o.getClass().getSimpleName() + "] 中的监听器:" + listener.getClass().getSimpleName() + ",触发了事件：" + event.getType());
    }

    public static void start(Object o, Object... params) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("调用了 [" + o.getClass().getSimpleName() + "] 的初始化方法");
            if (params.length > 0) {
                System.out.println("  -->需要的参数");
                Arrays.stream(params).forEach(ob -> System.out.print("    " + ob));
            }
        }
    }

    public static void add(Object o, Object listener) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("向 [" + o.getClass().getSimpleName() + "] 中添加了监听器:" + listener);
    }

    public static void setState(Object o, Boolean check, LifecycleState state) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            String c = check ? "需要" : "不需要";
            System.out.println(o.getClass().getSimpleName() + " 设置状态:" + state.name() + "," + c + "校验原子性");
        }
    }

    public static void printLocalParam(Object o, String method, String key, Object value) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print")))
            System.out.println("类 [" + o.getClass().getSimpleName() + "] 中方法:" + method + "()的局部变量" + key + "的值是:" + value);

    }
}
