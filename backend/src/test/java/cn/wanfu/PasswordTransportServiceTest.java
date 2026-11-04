package cn.wanfu;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordTransportServiceTest {
    final Instant now=Instant.parse("2032-05-17T00:00:00Z");
    Clock clock;
    PasswordTransportService service;

    @BeforeEach void setup() {
        clock=mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        service=new PasswordTransportService(clock);
    }
    Map<String,Object> request(Map<String,Object> challenge,String purpose,String username,String password) {
        return PasswordTransportTestClient.encrypt(challenge,purpose,Map.of("username",username,"password",password));
    }
    String decode(Map<String,Object> body) { return service.decode(body,PasswordTransportService.Purpose.LOGIN); }
    void bad(Map<String,Object> body) {
        assertThatThrownBy(()->decode(body)).isInstanceOfSatisfying(ApiException.class,e->{
            assertThat(e.status).isEqualTo(400);
            assertThat(e.getMessage()).isEqualTo("密码加密请求无效或已过期");
        });
    }
    @Test void roundTripPreservesPasswordAndNewChallengesUseSameValidPublicKey() {
        var first=service.challenge(null);
        var second=service.challenge("LOGIN");
        assertThat(first.get("expiresAt")).isEqualTo("2032-05-17T00:01:00Z");
        assertThat(first.get("algorithm")).isEqualTo("RSA-OAEP-256");
        assertThat(second.get("publicKey")).isEqualTo(first.get("publicKey"));
        assertThat(first.get("challengeId").toString()).matches("[A-Za-z0-9_-]{43}");
        assertThat(second.get("challengeId")).isNotEqualTo(first.get("challengeId"));
        assertThat(decode(request(first,"LOGIN","  用户😀  ","  Pässword中文123!  "))).isEqualTo("  Pässword中文123!  ");
        assertThat(decode(request(second,"LOGIN","admin","admin"))).isEqualTo("admin");
    }
    @Test void labelMatchesLiteralJavascriptJsonStringifyIncludingUnicodeAndEscapes() throws Exception {
        var challenge=service.challenge("LOGIN");
        String username=" 张\"\\\n\u000b😀 ";
        String json="[\"wanfu-password-v1\",\"LOGIN\",\""+challenge.get("challengeId")+"\",\"张\\\"\\\\\\n\\u000b😀\"]";
        String ciphertext=PasswordTransportTestClient.encrypt(challenge,"secret".getBytes(StandardCharsets.UTF_8),json.getBytes(StandardCharsets.UTF_8));
        assertThat(decode(Map.of("username",username,"challengeId",challenge.get("challengeId"),"encryptedPassword",ciphertext))).isEqualTo("secret");
    }
    @Test void replayFailsAndFreshIdAllowsAnotherAttempt() {
        var body=request(service.challenge("LOGIN"),"LOGIN","admin","admin");
        assertThat(decode(body)).isEqualTo("admin"); bad(body);
        assertThat(decode(request(service.challenge("LOGIN"),"LOGIN","admin","admin"))).isEqualTo("admin");
    }
    @Test void tamperedCiphertextConsumesChallengeEvenIfOriginalIsRetried() {
        var original=request(service.challenge("LOGIN"),"LOGIN","admin","do-not-leak");
        var tampered=new LinkedHashMap<>(original);
        byte[] bytes=Base64.getDecoder().decode(original.get("encryptedPassword").toString()); bytes[20]^=1;
        tampered.put("encryptedPassword",Base64.getEncoder().encodeToString(bytes));
        bad(tampered); bad(original);
    }
    @Test void malformedMissingOrWrongTypedCiphertextAlsoConsumesChallenge() {
        for(Object value:Arrays.asList("not-base64!","",7,null,Base64.getEncoder().encodeToString(new byte[255]))) {
            var body=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
            var broken=new LinkedHashMap<>(body); broken.put("encryptedPassword",value);
            bad(broken); bad(body);
        }
    }
    @Test void usernameAndChallengeIdAreBoundToCiphertext() {
        var original=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var otherUser=new LinkedHashMap<>(original); otherUser.put("username","teacher"); bad(otherUser); bad(original);
        var first=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var second=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var otherId=new LinkedHashMap<>(first); otherId.put("challengeId",second.get("challengeId"));
        bad(otherId); bad(second); assertThat(decode(first)).isEqualTo("secret");
        bad(Map.of("username","admin","challengeId","unknown","encryptedPassword",first.get("encryptedPassword")));
    }
    @Test void purposeIsCheckedInBothChallengeAndOaepLabel() {
        var create=request(service.challenge("CREATE_USER"),"CREATE_USER","admin","secret"); bad(create);
        assertThatThrownBy(()->service.decode(create,PasswordTransportService.Purpose.CREATE_USER)).isInstanceOf(ApiException.class);
        var wrongLabel=request(service.challenge("LOGIN"),"CREATE_USER","admin","secret"); bad(wrongLabel);
        var createWithLoginLabel=request(service.challenge("CREATE_USER"),"LOGIN","teacher","secret");
        assertThatThrownBy(()->service.decode(createWithLoginLabel,PasswordTransportService.Purpose.CREATE_USER)).isInstanceOf(ApiException.class);
        var valid=request(service.challenge("CREATE_USER"),"CREATE_USER","teacher","secret");
        assertThat(service.decode(valid,PasswordTransportService.Purpose.CREATE_USER)).isEqualTo("secret");
        assertThatThrownBy(()->service.challenge("login")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(400));
    }
    @Test void challengeExpiresExactlyAtSixtySeconds() {
        var before=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var boundary=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        when(clock.instant()).thenReturn(now.plusSeconds(60).minusNanos(1));
        assertThat(decode(before)).isEqualTo("secret");
        when(clock.instant()).thenReturn(now.plusSeconds(60)); bad(boundary);
        when(clock.instant()).thenReturn(now); bad(boundary);
    }
    @Test void concurrentConsumersHaveExactlyOneSuccess() throws Exception {
        var body=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var start=new CountDownLatch(1); var accepted=new AtomicInteger(); var rejected=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(16); List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<16;i++) jobs.add(pool.submit(()->{ start.await(); try { assertThat(decode(body)).isEqualTo("secret"); accepted.incrementAndGet(); } catch(ApiException e) { assertThat(e.status).isEqualTo(400); rejected.incrementAndGet(); } return null; }));
            start.countDown(); for(var job:jobs) job.get(10,TimeUnit.SECONDS);
            assertThat(accepted.get()).isEqualTo(1); assertThat(rejected.get()).isEqualTo(15);
        } finally { pool.shutdownNow(); }
    }
    @Test void capacityIsAtomicAndExpiredEntriesAreCleared() throws Exception {
        List<Map<String,Object>> issued=new CopyOnWriteArrayList<>();
        for(int i=0;i<2040;i++) issued.add(service.challenge("LOGIN"));
        var start=new CountDownLatch(1); var full=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(16); List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<16;i++) jobs.add(pool.submit(()->{ start.await(); try { issued.add(service.challenge("LOGIN")); } catch(ApiException e) { assertThat(e.status).isEqualTo(503); full.incrementAndGet(); } return null; }));
            start.countDown(); for(var job:jobs) job.get(10,TimeUnit.SECONDS);
            assertThat(issued).hasSize(2048); assertThat(full.get()).isEqualTo(8);
            assertThat(issued.stream().map(c->c.get("challengeId")).distinct().count()).isEqualTo(2048);
            assertThatThrownBy(()->service.challenge("LOGIN")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503));
            assertThat(decode(request(issued.get(0),"LOGIN","admin","secret"))).isEqualTo("secret");
            service.challenge("LOGIN");
            when(clock.instant()).thenReturn(now.plusSeconds(60));
            for(int i=0;i<2048;i++) service.challenge("LOGIN");
            assertThatThrownBy(()->service.challenge("LOGIN")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(503));
        } finally { pool.shutdownNow(); }
    }
    @Test void passwordsAreLimitedToSeventyTwoUtf8BytesWithoutTrimming() {
        for(String password:List.of("a".repeat(72),"中".repeat(24),"😀".repeat(18)," "))
            assertThat(decode(request(service.challenge("LOGIN"),"LOGIN","admin",password))).isEqualTo(password);
        for(String password:List.of("a".repeat(73),"中".repeat(25),"😀".repeat(19),"")) {
            var body=request(service.challenge("LOGIN"),"LOGIN","admin",password); bad(body); bad(body);
        }
    }
    @Test void invalidUtf8IsRejectedWithoutReplacementCharacters() {
        for(byte[] bytes:List.of(new byte[]{(byte)0xc3,0x28},new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80},new byte[]{(byte)0xff})) {
            var body=PasswordTransportTestClient.encrypt(service.challenge("LOGIN"),"LOGIN",Map.of("username","admin"),bytes);
            bad(body); bad(body);
        }
    }
    @Test void plaintextPresenceAndInvalidUsernameConsumeChallenge() {
        for(Object password:Arrays.asList("secret",null)) {
            var body=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
            var plain=new LinkedHashMap<>(body); plain.put("password",password); bad(plain); bad(body);
        }
        var body=request(service.challenge("LOGIN"),"LOGIN","admin","secret");
        var invalid=new LinkedHashMap<>(body); invalid.remove("username"); bad(invalid); bad(body);
        bad(Map.of("username","admin","password","secret")); bad(Map.of());
    }
}
