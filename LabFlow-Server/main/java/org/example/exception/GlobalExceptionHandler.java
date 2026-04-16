package org.example.exception;

import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Result;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result hanlerException(Exception e){
        e.printStackTrace();
        //类似三元运算符 有错误信息则返回错误信息 否则自定义
        return Result.error(StringUtils.hasLength(e.getMessage()) ? e.getMessage() : "操作失败，具体查看后台输出日志");
    }
    /**
     * 专门拦截并处理咱们刚才写的 CustomException 业务异常
     */
    @ExceptionHandler(CustomException.class)
    public Result handleCustomException(CustomException e) {
        // 业务异常通常是用户操作不当导致的（比如搜索了不存在的笔记），打印个 warn 级别日志即可
        log.warn("触发业务异常: {}", e.getMessage());

        // 这里的 Result.error() 需要根据你自己的 Result 类中的方法名来写
        return Result.error(e.getMessage());
    }
}
