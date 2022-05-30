package org.apache.catalina.lj.listener;

import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;

public class SelfLifeCycleListener implements LifecycleListener {
    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        System.out.println("[ " + event.getSource().getClass().getSimpleName() + " ]的" + event.getType() + "生命周期方法被调用");
    }
}
