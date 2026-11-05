package com.zhiyu.garden;

import com.zhiyu.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {GardenController.class, GardenChatController.class, SpeechController.class, ImageDraftController.class})
public class GardenErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(ApiResponse.failure(error.getStatusCode().value(),error.getReason()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(400,"请检查必填项、字数和评分范围"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(ApiResponse.failure(413,"上传文件过大，图片请限制在 5 MB 以内，录音请限制在 30 秒以内。"));
    }
    @ExceptionHandler({MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidAudioRequest(Exception error) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(400,"请检查上传文件及对应的识别授权后重试。"));
    }
}
