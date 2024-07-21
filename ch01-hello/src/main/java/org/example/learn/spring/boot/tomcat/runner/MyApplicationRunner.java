package org.example.learn.spring.boot.tomcat.runner;

import org.example.learn.spring.boot.tomcat.listener.MySpringApplicationRunListener;
import org.example.learn.spring.boot.tomcat.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.Map;
import java.util.Set;


/**
 * ApplicationRunner实在容器初始化完成后被调用的,所以可以使用@Component注解
 */
@Component
public class MyApplicationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(MySpringApplicationRunListener.class);

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("---------------MyApplicationRunner start---------------------");

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        // HandlerMethod无法序列化,因为循环依赖
        handlerMethods.forEach((requestMappingInfo, handlerMethod) -> {
            logger.info("Mapping:{}", JsonUtils.toJsonString(requestMappingInfo));
            PatternsRequestCondition patternsCondition = requestMappingInfo.getPatternsCondition();
            Set<String> patterns = patternsCondition.getPatterns();
            if (!CollectionUtils.isEmpty(patterns)) {
                patterns.forEach(pathPattern -> {
                    logger.info("pathPattern: {}", pathPattern);
                });
            }
        });

        logger.info("---------------MyApplicationRunner end---------------------");
    }
}
