package cn.wanfu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PasswordTransportService {
    public enum Purpose { LOGIN, CREATE_USER }
    private record Challenge(Purpose purpose, Instant expiresAt) {}
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final KeyPair keys;
    private final String publicKey;
    private final Map<String,Challenge> challenges=new HashMap<>();

    public PasswordTransportService(Clock clock) {
        this.clock=clock;
        try {
            var generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048,random);
            keys=generator.generateKeyPair();
            publicKey=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        } catch(GeneralSecurityException e) { throw new IllegalStateException("无法初始化密码加密服务",e); }
    }

    // Match JSON.stringify: compact UTF-8 JSON, including lowercase control-character escapes.
    private final ObjectMapper labelJson=JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();

    private synchronized Challenge consume(Object id) {
        return id instanceof String value?challenges.remove(value):null;
    }

    public String decode(Map<String,Object> body,Purpose purpose) {
        Challenge challenge=consume(body.get("challengeId"));
        if(body.containsKey("password") || challenge==null || challenge.purpose()!=purpose || !clock.instant().isBefore(challenge.expiresAt())) throw invalid();
        byte[] plaintext=null;
        try {
            String username=Db.required(body,"username",80);
            if(!(body.get("encryptedPassword") instanceof String encrypted) || encrypted.length()!=344) throw invalid();
            byte[] ciphertext=Base64.getDecoder().decode(encrypted);
            if(ciphertext.length!=256) throw invalid();
            byte[] label=labelJson.writeValueAsString(List.of("wanfu-password-v1",purpose.name(),body.get("challengeId"),username)).getBytes(StandardCharsets.UTF_8);
            var cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE,keys.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,new PSource.PSpecified(label)));
            plaintext=cipher.doFinal(ciphertext);
            if(plaintext.length==0 || plaintext.length>72) throw invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext)).toString();
        } catch(GeneralSecurityException | IOException | IllegalArgumentException | ApiException e) {
            throw invalid();
        } finally { if(plaintext!=null) Arrays.fill(plaintext,(byte)0); }
    }

    private static ApiException invalid() { return ApiException.bad("密码加密请求无效或已过期"); }

    public synchronized Map<String,Object> challenge(String purpose) {
        Purpose bound;
        try { bound=purpose==null?Purpose.LOGIN:Purpose.valueOf(purpose); }
        catch(IllegalArgumentException e) { throw ApiException.bad("无效的密码用途"); }
        Instant now=clock.instant();
        challenges.values().removeIf(c->!now.isBefore(c.expiresAt()));
        if(challenges.size()>=2048) throw new ApiException(503,"密码挑战暂不可用，请稍后重试");
        String id;
        do {
            byte[] bytes=new byte[32]; random.nextBytes(bytes);
            id=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while(challenges.containsKey(id));
        Instant expiresAt=now.plusSeconds(60);
        challenges.put(id,new Challenge(bound,expiresAt));
        return Map.of("challengeId",id,"publicKey",publicKey,"algorithm","RSA-OAEP-256","expiresAt",expiresAt.toString());
    }
}
