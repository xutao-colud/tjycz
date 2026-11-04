package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:safety;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/safety-uploads"})
@ActiveProfiles("local")
@Import(SafetyIntegrationTest.TimeConfig.class)
class SafetyIntegrationTest {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock safetyClock() { return Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"),Application.ZONE); }
    }
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String boss,principal,today;
    @BeforeEach void login() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=demo("ADMIN"); principal=demo("CAMPUS_ADMIN"); today=sec.today().toString();
    }
    ResponseEntity<String> exchange(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(token!=null) headers.setBearerAuth(token);
        return http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
    }
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        if(method==HttpMethod.POST && status==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,sec,body);
        var response=exchange(method,path,token,body);
        assertThat(response.getStatusCode().value()).as(method+" "+path+": "+response.getBody()).isEqualTo(status);
        try { return json.readTree(response.getBody()); } catch(Exception error) { throw new AssertionError(error); }
    }
    JsonNode post(String path,String token,Object body) { return call(HttpMethod.POST,path,token,body,200); }
    JsonNode get(String path,String token) { return call(HttpMethod.GET,path,token,null,200); }
    String demo(String role) { return post("/auth/demo",null,Map.of("role",role)).path("token").asText(); }
    String key() { return UUID.randomUUID().toString(); }
    String token(long id) { return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString(); }
    long user(String role,String name) { return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",key(),"fixture-unused-hash",name,role); }
    record Fixture(long student,long classroom,long guardian,long teacherId,String parent,String teacher,String colleague) {}
    Fixture fixture() {
        long classroom=post("/classes",boss,Map.of("campusId",1,"name","安全验收班-"+key(),"teacherName","虚构安全老师")).path("id").asLong();
        long student=post("/students",boss,Map.of("name","虚构接送学生","classId",classroom,"gender","男","grade","三年级","parentName","虚构监护人","parentPhone","13800001234")).path("id").asLong();
        long guardian=user("PARENT","虚构监护人"),teacher=user("TEACHER","虚构安全老师"),colleague=user("TEACHER","虚构协作老师");
        db.update("INSERT INTO parent_student VALUES(?,?)",guardian,student); db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,classroom); db.update("INSERT INTO teacher_class VALUES(?,?)",colleague,classroom);
        return new Fixture(student,classroom,guardian,teacher,token(guardian),token(teacher),token(colleague));
    }
    long fundedSchedule(Fixture f,String date) {
        long course=post("/courses",boss,Map.of("campusId",1,"name","安全验收课程-"+key(),"type","EVENING","unit","次","price",1000,"warningThreshold",1)).path("id").asLong();
        long account=post("/enrollments",boss,Map.of("studentId",f.student(),"courseId",course)).path("accountId").asLong();
        long renewal=post("/renewals",boss,Map.of("accountId",account,"amount",5000,"lessons",5,"giftLessons",0,"paymentMethod","CASH","note","虚构安全验收充值","requestId",key())).path("id").asLong();
        post("/renewals/"+renewal+"/confirm",boss,Map.of());
        post("/schedules",boss,Map.of("classId",f.classroom(),"courseId",course,"date",date,"startTime","16:00","endTime","18:00","units",2));
        return account;
    }
    JsonNode account(Fixture f,long id) { for(var row:get("/accounts?studentId="+f.student(),boss)) if(row.path("id").asLong()==id) return row; throw new AssertionError("虚构课程账户缺失"); }
    JsonNode attendance(Fixture f,String actor,boolean checkout,String date,String historicalReason) {
        var body=new LinkedHashMap<String,Object>(Map.of("studentIds",List.of(f.student()),"date",date));
        if(historicalReason!=null) body.put("historicalReason",historicalReason);
        return post(checkout?"/attendance/check-out":"/attendance/check-in",actor,body).path("results").get(0);
    }
    void checkIn(Fixture f) { assertThat(attendance(f,f.teacher(),false,today,null).path("success").asBoolean()).isTrue(); }
    void deniedCheckout(Fixture f,String actor) { assertThat(attendance(f,actor,true,today,null).path("success").asBoolean()).isFalse(); }
    JsonNode leave(Fixture f,String author) { return post("/leaves",author,Map.of("studentId",f.student(),"date",today,"reason","虚构验收：整日请假","requestId",key())); }
    Map<String,Object> leaveAction(JsonNode row,boolean approved) { return Map.of("expectedVersion",row.path("version").asInt(),"approved",approved,"note",approved?"已与监护人核对，请假批准":"请补充请假原因，暂不批准"); }
    Map<String,Object> pickupBody(Fixture f) { return new LinkedHashMap<>(Map.of("studentId",f.student(),"date",today,"pickupName","虚构接送人","relationship","亲属","phone","13800001234","requestId",key())); }
    JsonNode pickup(Fixture f) { return post("/pickups",f.parent(),pickupBody(f)); }
    String pickupPath(JsonNode row) { return "/pickups/"+row.path("id").asLong(); }
    Map<String,Object> verification(JsonNode row,String code) { return new LinkedHashMap<>(Map.of("expectedVersion",row.path("version").asInt(),"pickupName","虚构接送人","phoneLast4","1234","code",code,"confirmedIdentity",true,"requestId",key())); }
    JsonNode verify(Fixture f,JsonNode row,String actor,String code) { return post(pickupPath(row)+"/verify",actor,verification(row,code)); }
    JsonNode current(Fixture f,JsonNode row) { return get(pickupPath(row),f.teacher()); }
    void unbind(Fixture f) { call(HttpMethod.PUT,"/users/"+f.guardian()+"/access",boss,Map.of("studentIds",List.of()),200); }
    List<String> actions(JsonNode row) { List<String> result=new ArrayList<>(); for(var event:row.path("logs")) result.add(event.path("action").asText()); return result; }
    void noPickupSecrets(JsonNode row) { assertThat(row.has("codeHash")).isFalse(); assertThat(row.has("requestHash")).isFalse(); assertThat(row.has("requestId")).isFalse(); }

    @Test void approvedLeaveNeedsIndependentReviewAndCancellationRestoresCheckInWithoutConsumption() {
        var f=fixture(); long account=fundedSchedule(f,today); var requested=leave(f,f.parent());
        String path="/leaves/"+requested.path("id").asLong();
        assertThat(requested.path("status").asText()).isEqualTo("REQUESTED"); assertThat(requested.path("canReview").asBoolean()).isFalse();
        call(HttpMethod.POST,path+"/review",f.parent(),leaveAction(requested,true),403);
        call(HttpMethod.POST,path+"/review",f.teacher(),leaveAction(requested,true),403);
        var stale=new LinkedHashMap<>(leaveAction(requested,true)); stale.put("expectedVersion",requested.path("version").asInt()+1);
        call(HttpMethod.POST,path+"/review",principal,stale,409);
        var approved=post(path+"/review",principal,leaveAction(requested,true));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("reviewedBy").asLong()).isEqualTo(get("/me",principal).path("id").asLong());
        assertThat(post(path+"/review",principal,leaveAction(requested,true)).path("logs").size()).isEqualTo(2);
        assertThat(attendance(f,f.teacher(),false,today,null).path("success").asBoolean()).isFalse(); deniedCheckout(f,f.teacher());
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("5");
        assertThat(account(f,account).path("consumed").decimalValue()).isEqualByComparingTo("0");
        assertThat(db.count("SELECT COUNT(*) FROM settlement WHERE student_id=?",f.student())).isZero();
        var cancelled=post(path+"/cancel",f.parent(),Map.of("expectedVersion",approved.path("version").asInt(),"note","安排已变更，恢复正常到校"));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(actions(cancelled)).containsExactly("REQUESTED","APPROVED","CANCELLED"); checkIn(f);
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("5");
    }

    @Test void leaveSelfReviewIsRefusedAndRejectionDoesNotBlockAttendance() {
        var f=fixture(); var requested=leave(f,principal); String path="/leaves/"+requested.path("id").asLong();
        call(HttpMethod.POST,path+"/review",principal,leaveAction(requested,true),400);
        assertThat(get(path,principal).path("status").asText()).isEqualTo("REQUESTED");
        var rejected=post(path+"/review",boss,leaveAction(requested,false));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reviewComment").asText()).contains("暂不批准");
        assertThat(actions(rejected)).containsExactly("REQUESTED","REJECTED"); checkIn(f);
        call(HttpMethod.POST,path+"/cancel",f.parent(),Map.of("expectedVersion",rejected.path("version").asInt(),"note","不能撤销已驳回记录"),400);
        var other=fixture(); var pending=leave(other,other.parent());
        assertThat(post("/leaves/"+pending.path("id").asLong()+"/cancel",other.parent(),Map.of("expectedVersion",pending.path("version").asInt(),"note","撤回申请")).path("status").asText()).isEqualTo("CANCELLED"); checkIn(other);
    }

    @Test void safetyDetailsAndMutationsEnforceCurrentStudentAndTeacherBindings() {
        var a=fixture(); var b=fixture(); var leave=leave(b,b.parent()); var pickup=pickup(b); String leavePath="/leaves/"+leave.path("id").asLong();
        for(String unauthorized:List.of(a.parent(),a.teacher())) {
            call(HttpMethod.GET,leavePath,unauthorized,null,403); call(HttpMethod.GET,"/leaves?date="+today+"&studentId="+b.student(),unauthorized,null,403);
            call(HttpMethod.GET,pickupPath(pickup),unauthorized,null,403); call(HttpMethod.GET,"/pickups?date="+today+"&studentId="+b.student(),unauthorized,null,403);
            call(HttpMethod.POST,"/leaves",unauthorized,Map.of("studentId",b.student(),"date",today,"reason","越权申请","requestId",key()),403);
        }
        call(HttpMethod.POST,"/pickups",a.parent(),pickupBody(b),403);
        call(HttpMethod.POST,pickupPath(pickup)+"/revoke",a.parent(),Map.of("expectedVersion",0,"note","越权撤销"),403);
        call(HttpMethod.POST,pickupPath(pickup)+"/verify",a.teacher(),verification(pickup,pickup.path("pickupCode").asText()),403);
        call(HttpMethod.POST,"/attendance/check-in",a.teacher(),Map.of("studentIds",List.of(b.student()),"date",today),403);
        call(HttpMethod.POST,"/attendance/check-out",a.parent(),Map.of("studentIds",List.of(a.student()),"date",today),403);
        call(HttpMethod.POST,"/pickups",b.teacher(),pickupBody(b),403);
        assertThat(get("/leaves?date="+today,a.parent()).size()).isZero(); assertThat(get("/pickups?date="+today,a.teacher()).size()).isZero();
    }

    @Test void pickupCodeIsRevealedOnceAndTeacherMustConfirmAllIdentityEvidence() {
        var f=fixture(); checkIn(f); var body=pickupBody(f); var authorization=post("/pickups",f.parent(),body); String code=authorization.path("pickupCode").asText();
        assertThat(code).matches("[0-9]{6}"); noPickupSecrets(authorization);
        var replay=post("/pickups",f.parent(),body); assertThat(replay.path("id")).isEqualTo(authorization.path("id")); assertThat(replay.has("pickupCode")).isFalse();
        var parentView=get(pickupPath(authorization),f.parent()); noPickupSecrets(parentView); assertThat(parentView.has("pickupCode")).isFalse();
        var teacherView=current(f,authorization); noPickupSecrets(teacherView); assertThat(teacherView.has("phone")).isFalse(); assertThat(teacherView.path("phoneMasked").asText()).endsWith("1234");
        for(String missing:List.of("pickupName","phoneLast4","code","confirmedIdentity")) {
            var invalid=verification(authorization,code); invalid.remove(missing); call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),invalid,400);
        }
        for(var change:List.of(Map.<String,Object>of("code","12345"),Map.<String,Object>of("phoneLast4","abc4"),Map.<String,Object>of("confirmedIdentity",false))) {
            var invalid=verification(authorization,code); invalid.putAll(change); call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),invalid,400);
        }
        for(var change:List.of(Map.<String,Object>of("pickupName","其他接送人"),Map.<String,Object>of("phoneLast4","9999"))) {
            var mismatch=verification(authorization,code); mismatch.putAll(change); call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),mismatch,400);
        }
        deniedCheckout(f,f.teacher());
        var verified=verify(f,authorization,f.teacher(),code).path("verification"); assertThat(verified.path("actorId").asLong()).isEqualTo(f.teacherId());
        assertThat(Duration.between(LocalDateTime.parse(verified.path("verifiedAt").asText()),LocalDateTime.parse(verified.path("expiresAt").asText()))).isEqualTo(Duration.ofMinutes(10));
        assertThat(current(f,authorization).path("canCheckout").asBoolean()).isTrue();
        assertThat(get(pickupPath(authorization),f.colleague()).path("canCheckout").asBoolean()).isFalse();
    }

    @Test void invalidAuthorizationWindowsRevocationAndGuardianUnbindingArePersistentlyBlocked() {
        var absent=fixture(); var absentPickup=pickup(absent);
        call(HttpMethod.POST,pickupPath(absentPickup)+"/verify",absent.teacher(),verification(absentPickup,absentPickup.path("pickupCode").asText()),400);
        assertThat(actions(current(absent,absentPickup))).containsExactly("ACTIVE","VERIFY_REJECTED"); deniedCheckout(absent,absent.teacher());
        var future=fixture(); checkIn(future); var futureBody=pickupBody(future); futureBody.put("date",sec.today().plusDays(1).toString());
        var futurePickup=post("/pickups",future.parent(),futureBody);
        call(HttpMethod.POST,pickupPath(futurePickup)+"/verify",future.teacher(),verification(futurePickup,futurePickup.path("pickupCode").asText()),400); deniedCheckout(future,future.teacher());
        var expired=fixture(); checkIn(expired); var expiredPickup=pickup(expired);
        db.update("UPDATE pickup_authorization SET expires_at=? WHERE id=?",sec.now().minusSeconds(1),expiredPickup.path("id").asLong());
        assertThat(current(expired,expiredPickup).path("effectiveStatus").asText()).isEqualTo("EXPIRED");
        call(HttpMethod.POST,pickupPath(expiredPickup)+"/verify",expired.teacher(),verification(expiredPickup,expiredPickup.path("pickupCode").asText()),400); deniedCheckout(expired,expired.teacher());
        var revoked=fixture(); checkIn(revoked); var revokedPickup=pickup(revoked);
        var cancelled=post(pickupPath(revokedPickup)+"/revoke",revoked.parent(),Map.of("expectedVersion",revokedPickup.path("version").asInt(),"note","临时接送安排已撤销"));
        call(HttpMethod.POST,pickupPath(cancelled)+"/verify",revoked.teacher(),verification(cancelled,revokedPickup.path("pickupCode").asText()),400); deniedCheckout(revoked,revoked.teacher());
        var detached=fixture(); checkIn(detached); var detachedPickup=pickup(detached); unbind(detached);
        call(HttpMethod.POST,pickupPath(detachedPickup)+"/verify",detached.teacher(),verification(detachedPickup,detachedPickup.path("pickupCode").asText()),403);
        assertThat(actions(current(detached,detachedPickup))).containsExactly("ACTIVE","VERIFY_REJECTED"); deniedCheckout(detached,detached.teacher());
        call(HttpMethod.GET,pickupPath(detachedPickup),detached.parent(),null,403);
    }

    @Test void fiveWrongCodesPersistAcrossRequestsAndLockForFifteenMinutes() {
        var f=fixture(); checkIn(f); var authorization=pickup(f); String code=authorization.path("pickupCode").asText(),wrong=code.equals("000000")?"000001":"000000";
        for(int attempt=1;attempt<=5;attempt++) {
            call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),verification(authorization,wrong),400);
            assertThat(get(pickupPath(authorization),f.parent()).path("failedAttempts").asInt()).isEqualTo(attempt);
        }
        call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),verification(authorization,code),429);
        var locked=get(pickupPath(authorization),f.parent()); assertThat(actions(locked)).containsExactly("ACTIVE","VERIFY_REJECTED","VERIFY_REJECTED","VERIFY_REJECTED","VERIFY_REJECTED","VERIFY_REJECTED","VERIFY_REJECTED");
        assertThat(locked.path("logs").get(6).path("detail").asText()).contains("15分钟"); deniedCheckout(f,f.teacher());
        db.update("UPDATE pickup_authorization SET last_failed_at=? WHERE id=?",sec.now().minusMinutes(15).minusSeconds(1),authorization.path("id").asLong());
        assertThat(verify(f,authorization,f.teacher(),code).path("success").asBoolean()).isTrue();
        var recovered=get(pickupPath(authorization),f.parent()); assertThat(recovered.path("failedAttempts").asInt()).isZero(); assertThat(recovered.path("lastFailedAt").isNull()).isTrue();
    }

    @Test void verificationIsActorBoundExpiresAndConcurrentCheckoutConsumesOnlyOnce() {
        var f=fixture(); long account=fundedSchedule(f,today); checkIn(f); var authorization=pickup(f); String code=authorization.path("pickupCode").asText();
        var first=verify(f,authorization,f.teacher(),code).path("verification"); deniedCheckout(f,f.colleague());
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("5");
        db.update("UPDATE pickup_verification SET expires_at=? WHERE id=?",sec.now().minusSeconds(1),first.path("id").asLong());
        deniedCheckout(f,f.teacher()); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse();
        var verified=verify(f,current(f,authorization),f.teacher(),code).path("verification");
        List<CompletableFuture<JsonNode>> checkouts=new ArrayList<>();
        for(int i=0;i<6;i++) checkouts.add(CompletableFuture.supplyAsync(()->attendance(f,f.teacher(),true,today,null)));
        for(var result:checkouts) assertThat(result.join().path("success").asBoolean()).isTrue();
        var used=current(f,authorization); assertThat(used.path("status").asText()).isEqualTo("USED"); assertThat(used.path("canCheckout").asBoolean()).isFalse();
        assertThat(used.path("verification").path("id")).isEqualTo(verified.path("id")); assertThat(used.path("verification").path("usedAt").isTextual()).isTrue();
        assertThat(Collections.frequency(actions(used),"USED")).isEqualTo(1);
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("3"); assertThat(account(f,account).path("consumed").decimalValue()).isEqualByComparingTo("2");
        assertThat(db.count("SELECT COUNT(*) FROM lesson_ledger WHERE account_id=? AND type='CONSUMPTION'",account)).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM growth_event WHERE student_id=? AND source_type='ATTENDANCE' AND action='CHECK_OUT'",f.student())).isEqualTo(1);
        assertThat(attendance(f,f.teacher(),true,today,null).path("success").asBoolean()).isTrue();
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("3");
    }

    @Test void rotatingPickupCodeInvalidatesPreviousVerificationAndRequiresFreshIdentityCheck() {
        var f=fixture(); checkIn(f); var authorization=pickup(f); String oldCode=authorization.path("pickupCode").asText(); verify(f,authorization,f.teacher(),oldCode);
        var verified=current(f,authorization);
        var rotated=post(pickupPath(authorization)+"/rotate-code",f.parent(),Map.of("expectedVersion",verified.path("version").asInt()));
        String newCode=rotated.path("pickupCode").asText(); assertThat(newCode).matches("[0-9]{6}").isNotEqualTo(oldCode); noPickupSecrets(rotated);
        assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse(); deniedCheckout(f,f.teacher());
        call(HttpMethod.POST,pickupPath(authorization)+"/verify",f.teacher(),verification(rotated,oldCode),400);
        verify(f,rotated,f.teacher(),newCode); var rechecked=current(f,authorization); assertThat(rechecked.path("canCheckout").asBoolean()).isTrue();
        verify(f,rechecked,f.colleague(),newCode); deniedCheckout(f,f.teacher());
        assertThat(get(pickupPath(authorization),f.colleague()).path("canCheckout").asBoolean()).isTrue();
        assertThat(attendance(f,f.colleague(),true,today,null).path("success").asBoolean()).isTrue();
    }

    @Test void revocationAndGuardianUnbindingInvalidatePreviouslyVerifiedCheckout() {
        var revoked=fixture(); checkIn(revoked); var authorization=pickup(revoked); verify(revoked,authorization,revoked.teacher(),authorization.path("pickupCode").asText());
        var row=current(revoked,authorization);
        post(pickupPath(row)+"/revoke",revoked.parent(),Map.of("expectedVersion",row.path("version").asInt(),"note","核验后家长取消接送"));
        deniedCheckout(revoked,revoked.teacher()); assertThat(current(revoked,row).path("canCheckout").asBoolean()).isFalse();
        var detached=fixture(); checkIn(detached); authorization=pickup(detached); verify(detached,authorization,detached.teacher(),authorization.path("pickupCode").asText()); unbind(detached);
        deniedCheckout(detached,detached.teacher());
        var invalid=current(detached,authorization); assertThat(invalid.path("canCheckout").asBoolean()).isFalse(); assertThat(invalid.path("canVerify").asBoolean()).isFalse();
        assertThat(invalid.path("status").asText()).isEqualTo("ACTIVE"); assertThat(Collections.frequency(actions(invalid),"USED")).isZero();
    }

    @Test void historicalCheckoutRequiresAdministratorReasonAndPreservesUnknownEventTimes() {
        var f=fixture(); String yesterday=sec.today().minusDays(1).toString(); long account=fundedSchedule(f,yesterday);
        assertThat(attendance(f,f.teacher(),false,yesterday,null).path("success").asBoolean()).isTrue();
        assertThat(attendance(f,f.teacher(),true,yesterday,"教师不能自行补录历史离校").path("success").asBoolean()).isFalse();
        assertThat(attendance(f,principal,true,yesterday,null).path("success").asBoolean()).isFalse();
        assertThat(attendance(f,principal,true,yesterday,"字".repeat(1001)).path("success").asBoolean()).isFalse();
        assertThat(account(f,account).path("consumed").decimalValue()).isEqualByComparingTo("0");
        assertThat(attendance(f,principal,true,yesterday,"已根据纸质接送登记核对历史离校事实，具体时间未知").path("success").asBoolean()).isTrue();
        JsonNode row=null; for(var a:get("/attendance?date="+yesterday+"&classId="+f.classroom(),principal)) if(a.path("studentId").asLong()==f.student()) row=a;
        assertThat(row).isNotNull(); assertThat(row.path("status").asText()).isEqualTo("LEFT"); assertThat(row.path("checkInTime").isNull()).isTrue(); assertThat(row.path("checkOutTime").isNull()).isTrue();
        assertThat(account(f,account).path("consumed").decimalValue()).isEqualByComparingTo("2");
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='HISTORICAL_CHECK_OUT' AND detail LIKE ?","student="+f.student()+",%")).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM pickup_verification WHERE student_id=?",f.student())).isZero();
        int attendanceEvents=0;
        for(var event:get("/growth?date="+yesterday+"&studentId="+f.student(),f.teacher()).path("timeline")) if(event.path("sourceType").asText().equals("ATTENDANCE")) { attendanceEvents++; assertThat(event.path("occurredAt").isNull()).isTrue(); }
        assertThat(attendanceEvents).isEqualTo(2);
    }
}
