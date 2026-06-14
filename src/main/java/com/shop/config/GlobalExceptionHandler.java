package com.shop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/**
 * 全局异常处理
 * 当代码报错时，显示具体的错误信息，而不是 Whitelabel Error Page
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ModelAndView handleError(HttpServletRequest req, Exception e) {
        log.error("请求出错: {} {}", req.getMethod(), req.getRequestURL(), e);

        ModelAndView mav = new ModelAndView("error");
        mav.addObject("message", e.getMessage());
        mav.addObject("url", req.getRequestURL());
        return mav;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView handleUploadError(HttpServletRequest req, MaxUploadSizeExceededException e) {
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("message", "文件过大，请上传小于 10MB 的图片");
        mav.addObject("url", req.getRequestURL());
        return mav;
    }
}
