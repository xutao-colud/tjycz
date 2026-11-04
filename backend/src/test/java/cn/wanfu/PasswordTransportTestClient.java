package cn.wanfu;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

final class PasswordTransportTestClient {
    static Map<String,Object> encrypt(Map<String,?> challenge,String purpose,Map<String,Object> body) {
        return encrypt(challenge,purpose,body,body.get("password").toString().getBytes(StandardCharsets.UTF_8));
    }

    static Map<String,Object> encrypt(Map<String,?> challenge,String purpose,Map<String,Object> body,byte[] password) {
        try {
            String username=body.get("username").toString().trim();
            byte[] label=JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build()
                .writeValueAsString(List.of("wanfu-password-v1",purpose,challenge.get("challengeId"),username)).getBytes(StandardCharsets.UTF_8);
            Map<String,Object> encrypted=new LinkedHashMap<>(body);
            encrypted.remove("password");
            encrypted.put("challengeId",challenge.get("challengeId"));
            encrypted.put("encryptedPassword",encrypt(challenge,password,label));
            return encrypted;
        } catch(Exception e) { throw new AssertionError(e); }
    }

    static String encrypt(Map<String,?> challenge,byte[] password,byte[] label) throws Exception {
        var key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(challenge.get("publicKey").toString())));
        var cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,new PSource.PSpecified(label)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(password));
    }
}
