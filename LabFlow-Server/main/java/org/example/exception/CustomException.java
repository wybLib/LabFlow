package org.example.exception;

/**
 * 自定义业务异常
 */
public class CustomException extends RuntimeException {
    
    // 可以扩展一个错误码字段，方便前端做特殊逻辑判断（比如 code=401 跳转登录页）
    private Integer code;

    /**
     * 只传错误提示信息的构造方法
     */
    public CustomException(String message) {
        super(message);
    }

    /**
     * 同时传入错误码和提示信息的构造方法
     */
    public CustomException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
    
    public void setCode(Integer code) {
        this.code = code;
    }
}