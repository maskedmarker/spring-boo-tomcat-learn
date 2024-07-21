package org.example.learn.spring.boot.tomcat.runner;

import com.alibaba.fastjson2.JSON;
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
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

/**
 * 经测试,fastjson也是无法对出现循环依赖的对象实现序列化的
 */
@Component
public class JsonTestApplicationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(MySpringApplicationRunListener.class);

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("---------------JsonTestApplicationRunner start---------------------");

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        // HandlerMethod无法序列化,因为循环依赖
        handlerMethods.forEach((requestMappingInfo, handlerMethod) -> {
            logger.info("requestMappingInfo:{}", JSON.toJSONString(requestMappingInfo));
//            logger.info("handlerMethod:{}", JSON.toJSONString(handlerMethod));
        });

        logger.info("---------------JsonTestApplicationRunner end---------------------");
    }
}
