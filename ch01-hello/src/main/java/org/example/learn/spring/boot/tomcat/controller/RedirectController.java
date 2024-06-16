package org.example.learn.spring.boot.tomcat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Calendar;

@Controller
@RequestMapping("/redirect")
public class RedirectController {

    @GetMapping("/echo")
    public String echo(String msg, HttpServletRequest request, HttpServletResponse response) {
        return "redirect:/hello/echo";
    }
}
