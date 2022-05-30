/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package javax.servlet;

import java.util.Set;

/**
 * ServletContainerInitializers (SCIs) are registered via an entry in the
 * file META-INF/services/javax.servlet.ServletContainerInitializer that must be
 * included in the JAR file that contains the SCI implementation.
 * <p>
 * SCI processing is performed regardless of the setting of metadata-complete.
 * SCI processing can be controlled per JAR file via fragment ordering. If
 * absolute ordering is defined, then only the JARs included in the ordering
 * will be processed for SCIs. To disable SCI processing completely, an empty
 * absolute ordering may be defined.
 * <p>
 * SCIs register an interest in annotations (class, method or field) and/or
 * types via the {@link javax.servlet.annotation.HandlesTypes} annotation which
 * is added to the class.
 *
 * @since Servlet 3.0
 */

/**
 * ServletContainerInitializers（的SCI）是通过在文件中的条目中寄存必须包含在包含SCI执行的JAR文件META-INF /服务/ javax.servlet.ServletContainerInitializer。
 * 是元数据完整的设定而不管进行SCI的处理。 SCI处理可以每JAR文件经由片段排序来控制。 如果绝对顺序被定义，则仅包括在排序中的JAR将被用于的SCI处理。 要禁用SCI处理完全，一个空的绝对排序可以被定义。
 * 寄存器的SCI经由在注释（类，方法或场）和/或类型的兴趣javax.servlet.annotation.HandlesTypes其被添加到类注释。
 */
// spring-mvc和tomcat交互的纽带?
// org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory#configureContext
public interface ServletContainerInitializer {

    /**
     * Receives notification during startup of a web application of the classes
     * within the web application that matched the criteria defined via the
     * {@link javax.servlet.annotation.HandlesTypes} annotation.
     *
     * @param c     The (possibly null) set of classes that met the specified
     *              criteria
     * @param ctx   The ServletContext of the web application in which the
     *              classes were discovered
     *
     * @throws ServletException If an error occurs
     */
    /**
     * 该web应用程序中的类的web应用程序的启动过程中接收通知匹配经由定义的标准javax.servlet.annotation.HandlesTypes注释。
     *
     * PARAMS：
     * c - 接收（可能为null）的类集能够满足指定的标准
     * CTX - 其中的类被发现的Web应用程序的ServletContext的
     * 抛出： ServletException -如果发生错误
     */
    void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException;
}
