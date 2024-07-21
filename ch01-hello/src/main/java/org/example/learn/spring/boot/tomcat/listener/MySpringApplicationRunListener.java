package org.example.learn.spring.boot.tomcat.listener;

import org.example.learn.spring.boot.tomcat.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

/**
 * SpringApplicationRunListener是在springboot容器启动中使用的,因此@Component/@Configuration注解不起作用
 * 只能通过spring.factories方式注入到容器中
 *
 */
public class MySpringApplicationRunListener implements SpringApplicationRunListener {

    private static final Logger logger = LoggerFactory.getLogger(MySpringApplicationRunListener.class);

    public MySpringApplicationRunListener(SpringApplication springApplication, String[] args) {

    }

    @Override
    public void started(ConfigurableApplicationContext context) {
        logger.info("---------------MySpringApplicationRunListener start---------------------");

        RequestMappingHandlerMapping requestMappingHandlerMapping = context.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        // HandlerMethod无法序列化,因为循环依赖
        handlerMethods.forEach((requestMappingInfo, handlerMethod) -> {
            logger.info("Mapping:{}", JsonUtils.toJsonString(requestMappingInfo));
        });

        logger.info("---------------MySpringApplicationRunListener end---------------------");
    }
}
