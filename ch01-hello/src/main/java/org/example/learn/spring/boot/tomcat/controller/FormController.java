package org.example.learn.spring.boot.tomcat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Calendar;
import java.util.Collections;

@Controller
@RequestMapping("/form")
public class FormController {

    @RequestMapping("/hello")
    @ResponseBody
    public void hello(HttpServletRequest request, HttpServletResponse response) {

        // getParameterNames()不能获取到File参数
        Collections.list(request.getParameterNames()).forEach(k -> {
            System.out.println("k = " + k);
            System.out.println("v = " + request.getParameter(k));
            System.out.println("-------------");
        });

        // 如下方式才能获取到File类型的
        if (request instanceof MultipartRequest) {
            MultipartRequest multipartRequest = (MultipartRequest) request;
            multipartRequest.getFileNames().forEachRemaining(k -> {
                    System.out.println("k = " + k);
            });
        }
    }
}
