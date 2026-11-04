package cn.wanfu;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String message) { super(message); this.status=status; this.code="HTTP_"+status; }
    static ApiException bad(String message) { return new ApiException(400,message); }
    static ApiException forbidden() { return new ApiException(403,"无权访问此资源"); }
    static ApiException missing() { return new ApiException(404,"资源不存在"); }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage(),"code",e.code)); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("message","数据重复或存在关联约束","code","CONFLICT")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size(Exception e) { return ResponseEntity.status(413).body(Map.of("message","文件超过20MB","code","FILE_TOO_LARGE")); }
    @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","请求参数格式错误","code","INVALID_REQUEST")); }
}
