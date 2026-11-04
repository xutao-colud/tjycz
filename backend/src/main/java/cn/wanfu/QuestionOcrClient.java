package cn.wanfu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QuestionOcrClient {
    final ObjectMapper json;final String base,key;final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public QuestionOcrClient(ObjectMapper json,@Value("${app.integrations.ocr-url:}")String base,@Value("${app.integrations.ocr-key-file:}")String keyFile) {
        this.json=json;this.base=base.replaceAll("/+$","");
        try {this.key=keyFile.isBlank()?"":Files.readString(Path.of(keyFile)).trim();}catch(java.io.IOException error){throw new IllegalStateException("无法读取OCR服务凭据文件",error);}
        if(!base.isBlank()){var uri=URI.create(base);if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null)throw new IllegalArgumentException("OCR服务地址无效");}
    }
    boolean configured(){return !base.isBlank()&&!key.isBlank();}
    Map<String,Object> status(){
        if(!configured())return Map.of("available",false,"message","识别服务尚未配置，可先手工录题");
        try {return call("GET","/health",null,5);}catch(Exception error){return Map.of("available",false,"message","识别服务暂未就绪，可重试或手工录题");}
    }
    Map<String,Object> recognize(byte[] image){
        if(!configured())throw new ApiException(503,"识别服务尚未配置");
        try{return call("POST","/recognize",Map.of("image",Base64.getEncoder().encodeToString(image)),180);}
        catch(ApiException error){throw error;}catch(Exception error){throw new ApiException(503,"识别服务连接失败或处理超时，请重试；原图已保留");}
    }
    Map<String,Object> call(String method,String route,Object body,int seconds)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(seconds)).header("X-OCR-Key",key);
        if(method.equals("POST"))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.GET();
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;
        try(var input=response.body()){bytes=input.readNBytes(32*1024*1024+1);}
        if(bytes.length>32*1024*1024)throw new ApiException(502,"识别结果过大，请裁小图片后重试");
        if(response.statusCode()!=200)throw new ApiException(503,"识别服务处理失败，请重试或使用手工框题");
        return json.readValue(bytes,new TypeReference<>(){});
    }
}
