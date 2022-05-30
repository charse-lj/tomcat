package org.apache.catalina.lj.listener;

import org.apache.catalina.ContainerEvent;
import org.apache.catalina.ContainerListener;

public class SelfContainerListener implements ContainerListener {
    @Override
    public void containerEvent(ContainerEvent event) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("触发的容器:" + event.getContainer() + ",触发的事件 ContainerEvent");
            System.out.println("    -->事件类型:" + event.getType() + ",data value:" + event.getData());
        }
    }
}
