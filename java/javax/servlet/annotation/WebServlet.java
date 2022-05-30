/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package javax.servlet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is used to declare the configuration of a
 * {@link javax.servlet.Servlet}. <br>
 *
 * If the name attribute is not defined, the fully qualified name of the class
 * is used.<br>
 * <br>
 *
 * At least one URL pattern MUST be declared in either the {@code value} or
 * {@code urlPattern} attribute of the annotation, but not both.<br>
 * <br>
 *
 * The {@code value} attribute is recommended for use when the URL pattern is
 * the only attribute being set, otherwise the {@code urlPattern} attribute
 * should be used.<br>
 * <br>
 *
 * The class on which this annotation is declared MUST extend
 * {@link javax.servlet.http.HttpServlet}. <br>
 * <br>
 *
 * E.g. <code>@WebServlet("/path")}<br>
 * public class TestServlet extends HttpServlet ... {</code><br>
 *
 * E.g.
 * <code>@WebServlet(name="TestServlet", urlPatterns={"/path", "/alt"}) <br>
 * public class TestServlet extends HttpServlet ... {</code><br>
 *
 * @since Servlet 3.0 (Section 8.1.1)
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WebServlet {

    /**
     * @return name of the Servlet
     *
     * 指定 Servlet 的 name 属性，等价于 <servlet-name>。如果没有显式指定，则该 Servlet 的取值即为类的全限定名
     */
    String name() default "";

    /**
     * A convenience method, to allow extremely simple annotation of a class.
     *
     * @return array of URL patterns
     * @see #urlPatterns()
     *
     * 该属性等价于 urlPatterns 属性。两个属性不能同时使用
     */
    String[] value() default {};

    /**
     * @return array of URL patterns to which this Filter applies
     *
     * 指定一组 Servlet 的 URL 匹配模式。等价于 <url-pattern> 标签。
     */
    String[] urlPatterns() default {};

    /**
     * @return load on startup ordering hint
     *
     * 指定 Servlet 的加载顺序，等价于 <load-on-startup> 标签。
     */
    int loadOnStartup() default -1;

    /**
     * @return array of initialization params for this Servlet
     *
     * 指定一组 Servlet 初始化参数，等价于 <init-param> 标签。
     */
    WebInitParam[] initParams() default {};

    /**
     * @return asynchronous operation supported by this Servlet
     *
     * 声明 Servlet 是否支持异步操作模式，等价于 <async-supported> 标签。
     */
    boolean asyncSupported() default false;

    /**
     * @return small icon for this Servlet, if present
     */
    String smallIcon() default "";

    /**
     * @return large icon for this Servlet, if present
     */
    String largeIcon() default "";

    /**
     * @return description of this Servlet, if present
     *
     * 该 Servlet 的描述信息，等价于 <description> 标签。
     */
    String description() default "";

    /**
     * @return display name of this Servlet, if present
     *
     * 该 Servlet 的显示名，通常配合工具使用，等价于 <display-name> 标签。
     */
    String displayName() default "";
}
