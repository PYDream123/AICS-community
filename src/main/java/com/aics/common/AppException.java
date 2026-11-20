package com.aics.common;

import org.springframework.http.HttpStatus;

/** 业务异常：code 给前端，message 给用户看，技术细节只进日志。 */
public class AppException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AppException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public AppException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static AppException notFound(String what) {
        return new AppException("NOT_FOUND", what + "不存在", HttpStatus.NOT_FOUND);
    }

    public static AppException badRequest(String message) {
        return new AppException("BAD_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
}
