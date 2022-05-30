package org.apache.catalina.lj.listener;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class SelfDefinePropertyChangeListener implements PropertyChangeListener {
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (Boolean.parseBoolean(System.getProperty("tomcat.self.print"))) {
            System.out.println("在" + evt.getSource().getClass().getSimpleName() + "中,触发了属性修改事件");
            System.out.println("    -->修改的属性:" + evt.getPropertyName());
            System.out.println("    -->属性值由:" + evt.getOldValue() + ",修改为:" + evt.getNewValue());
        }
    }
}
