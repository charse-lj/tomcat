package util.digister;

import org.apache.tomcat.util.digester.Digester;
import org.xml.sax.SAXException;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DigesterNamespaceExample {

    public static void main(String[] args) {
        try {
            // 创建 Digester 实例并开启命名空间支持
            Digester digester = new Digester();
            digester.setNamespaceAware(true);

            // 添加规则
            digester.addObjectCreate("person", Person.class.getName());
            digester.addSetProperties("person");
//            digester.addSetNext("person/name", "setName", String.class.getName());
//            digester.addSetProperties("person/age");
//            digester.addSetNext("person/age", "setAge", Person.class.getName());
            String path = System.getProperty("user.dir");
            // 解析 XML 文件
            Object parse = digester.parse(Files.newInputStream(Paths.get(path + "/test/util/digister/person.xml")));
            System.out.println(parse);
        } catch (IOException | SAXException e) {
            e.printStackTrace();
        }
    }

    public static void processPerson(Person person) {
        System.out.println(person);
    }
}
