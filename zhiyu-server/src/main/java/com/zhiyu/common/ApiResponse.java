package com.zhiyu.common;

import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, UUID.randomUUID().toString());
    }

    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null, UUID.randomUUID().toString());
    }
}
