package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true",
    "spring.datasource.url=jdbc:h2:mem:pickupmanagement;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/pickup-management-uploads"})
@ActiveProfiles("local")
@Import(PickupManagementIntegrationTest.TimeConfig.class)
class PickupManagementIntegrationTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> instant=new AtomicReference<>(Instant.parse("2026-10-28T02:00:00Z"));
        public ZoneId getZone() { return Application.ZONE; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant.get(); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock pickupClock() { return new MutableClock(); } }
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec; @Autowired MutableClock clock;
    String boss,today;
    @BeforeEach void prepare() {
        clock.instant.set(Instant.parse("2026-10-28T02:00:00Z"));
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText(); today=sec.today().toString();
    }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        if(method==HttpMethod.POST && status==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,sec,body);
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(token!=null) headers.setBearerAuth(token);
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path).isEqualTo(status);
        try { return json.readTree(response.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode get(String path,String actor) { return call(HttpMethod.GET,path,actor,null,200); }
    JsonNode post(String path,String actor,Object body) { return call(HttpMethod.POST,path,actor,body,200); }
    String token(long id) { return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString(); }
    record Fixture(long student,long classroom,long guardian,long teacherId,String parent,String teacher) {}
    Fixture fixture() {
        long classroom=post("/classes",boss,Map.of("campusId",1,"name","虚构接送管理班-"+key(),"teacherName","虚构老师")).path("id").asLong();
        long student=post("/students",boss,Map.of("name","虚构接送管理学生","classId",classroom,"gender","男","grade","三年级","parentName","虚构家长","parentPhone","13800001234")).path("id").asLong();
        long parent=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'PARENT')",key(),"fixture-unused-hash","虚构家长");
        long teacher=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'TEACHER')",key(),"fixture-unused-hash","虚构老师");
        db.update("INSERT INTO parent_student VALUES(?,?)",parent,student); db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,classroom);
        return new Fixture(student,classroom,parent,teacher,token(parent),token(teacher));
    }
    Fixture refreshTokens(Fixture f) { return new Fixture(f.student(),f.classroom(),f.guardian(),f.teacherId(),token(f.guardian()),token(f.teacherId())); }
    Map<String,Object> contactBody(Fixture f,String name) { return new LinkedHashMap<>(Map.of("studentId",f.student(),"name",name,"relationship","亲属","phone","13800001234","requestId",key())); }
    JsonNode contact(Fixture f,String name) { return post("/pickup-contacts",f.parent(),contactBody(f,name)); }
    Map<String,Object> edit(JsonNode row,String name) { return new LinkedHashMap<>(Map.of("expectedVersion",row.path("version").asInt(),"name",name,"relationship","亲属","phone","13800001234")); }
    Map<String,Object> authorize(Fixture f,JsonNode contact,String mode,String start,String end,String from,String until) {
        return new LinkedHashMap<>(Map.of("studentId",f.student(),"contactId",contact.path("id").asLong(),"expectedContactVersion",contact.path("version").asInt(),"mode",mode,"startDate",start,"endDate",end,"startTime",from,"endTime",until,"note","虚构授权备注","requestId",key()));
    }
    JsonNode current(JsonNode row,Fixture f) { return get("/pickups/"+row.path("id").asLong(),f.teacher()); }
    JsonNode verify(Fixture f,JsonNode row,String code) {
        return post("/pickups/"+row.path("id").asLong()+"/verify",f.teacher(),Map.of("pickupName",row.path("pickupName").asText(),"phoneLast4","1234","code",code,"confirmedIdentity",true,"expectedVersion",row.path("version").asInt(),"requestId",key()));
    }
    JsonNode attendance(Fixture f,boolean checkout) { return post(checkout?"/attendance/check-out":"/attendance/check-in",f.teacher(),Map.of("studentIds",List.of(f.student()),"date",sec.today().toString())).path("results").get(0); }
    void concurrentCheckout(Fixture f) { var pending=new ArrayList<CompletableFuture<JsonNode>>(); for(int i=0;i<6;i++) pending.add(CompletableFuture.supplyAsync(()->attendance(f,true))); for(var response:pending) assertThat(response.join().path("success").asBoolean()).isTrue(); }
    long fundTwoDays(Fixture f) {
        long course=post("/courses",boss,Map.of("campusId",1,"name","虚构跨日课程-"+key(),"type","EVENING","unit","次","price",1000,"warningThreshold",1)).path("id").asLong();
        long account=post("/enrollments",boss,Map.of("studentId",f.student(),"courseId",course)).path("accountId").asLong();
        long renewal=post("/renewals",boss,Map.of("accountId",account,"amount",5000,"lessons",5,"giftLessons",0,"paymentMethod","CASH","note","虚构测试续费","requestId",key())).path("id").asLong();
        post("/renewals/"+renewal+"/confirm",boss,Map.of());
        for(int offset=0;offset<2;offset++) post("/schedules",boss,Map.of("classId",f.classroom(),"courseId",course,"date",sec.today().plusDays(offset).toString(),"startTime","16:00","endTime","18:00","units",2));
        return account;
    }
    JsonNode account(Fixture f,long id) { for(var row:get("/accounts?studentId="+f.student(),boss)) if(row.path("id").asLong()==id) return row; throw new AssertionError("虚构账户缺失"); }

    @Test void currentGuardiansManageContactsAndDefaultsWhileStaffOnlyReadMaskedDetails() {
        var f=fixture(); var other=fixture(); var first=contact(f,"虚构接送人甲"); var body=contactBody(f,"虚构接送人乙"); body.put("isDefault",true); var second=post("/pickup-contacts",f.parent(),body);
        assertThat(first.path("isDefault").asBoolean()).isTrue();
        var rows=get("/pickup-contacts?studentId="+f.student(),f.parent()); assertThat(rows.size()).isEqualTo(2); assertThat(rows.get(0).path("id")).isEqualTo(second.path("id")); assertThat(rows.get(1).path("isDefault").asBoolean()).isFalse();
        var staff=get("/pickup-contacts?studentId="+f.student(),f.teacher()).get(0);
        assertThat(staff.has("phone")).isFalse(); assertThat(staff.path("phoneLast4").asText()).isEqualTo("1234"); assertThat(staff.path("canManage").asBoolean()).isFalse();
        assertThat(staff.has("requestHash")).isFalse(); assertThat(staff.has("requestId")).isFalse(); assertThat(staff.path("faceStatus").asText()).isEqualTo("NOT_CONFIGURED");
        call(HttpMethod.GET,"/pickup-contacts?studentId="+f.student(),other.parent(),null,403);
        call(HttpMethod.POST,"/pickup-contacts",boss,contactBody(f,"不应登记"),403);
        call(HttpMethod.PATCH,"/pickup-contacts/"+first.path("id"),f.teacher(),edit(first,"不应修改"),403);
        call(HttpMethod.DELETE,"/pickup-contacts/"+first.path("id"),f.teacher(),Map.of("expectedVersion",0),403);
        var refreshed=rows.get(1); var selected=post("/pickup-contacts/"+refreshed.path("id")+"/default",f.parent(),Map.of("expectedVersion",refreshed.path("version").asInt()));
        assertThat(selected.path("isDefault").asBoolean()).isTrue();
        call(HttpMethod.PATCH,"/pickup-contacts/"+selected.path("id"),f.parent(),edit(first,"过期编辑"),409);
        call(HttpMethod.DELETE,"/pickup-contacts/"+selected.path("id"),f.parent(),Map.of("expectedVersion",selected.path("version").asInt()),200);
        rows=get("/pickup-contacts?studentId="+f.student(),f.parent()); assertThat(rows.size()).isEqualTo(1); assertThat(rows.get(0).path("isDefault").asBoolean()).isTrue();
    }
    @Test void avatarLinksRequireMediaConsentAndDisappearFromParentAndStaffResponsesAfterWithdrawal() {
        var f=fixture();
        long image=db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,?,?,?,?,?,?)",f.guardian(),"虚构接送头像.png",key(),"image/png",100,sec.now(),1);
        var body=contactBody(f,"虚构有头像接送人"); body.put("avatarFileId",image);
        call(HttpMethod.POST,"/pickup-contacts",f.parent(),body,403);
        for(String scope:List.of("SERVICE","MEDIA")) post("/privacy/consents",f.parent(),Map.of("studentId",f.student(),"scope",scope,"granted",true,"policyVersion",PrivacyService.POLICY_VERSION));
        var row=post("/pickup-contacts",f.parent(),body); assertThat(row.path("avatar").path("id").asLong()).isEqualTo(image);
        assertThat(get("/pickup-contacts?studentId="+f.student(),f.teacher()).get(0).path("avatar").path("url").asText()).contains("ticket=").doesNotContain("token=");
        post("/privacy/consents",f.parent(),Map.of("studentId",f.student(),"scope","MEDIA","granted",false,"policyVersion",PrivacyService.POLICY_VERSION));
        for(String actor:List.of(f.parent(),f.teacher())) { var hidden=get("/pickup-contacts?studentId="+f.student(),actor).get(0); assertThat(hidden.path("avatar").isNull()).isTrue(); assertThat(hidden.path("avatarFileId").isNull()).isTrue(); }
        call(HttpMethod.GET,"/files/"+image+"/access",f.teacher(),null,403);
    }
    @Test void contactAndAuthorizationCreationAreIdempotentAndRejectIdentityCardFields() {
        var f=fixture(); var body=contactBody(f,"虚构常用接送人"); var row=post("/pickup-contacts",f.parent(),body);
        assertThat(post("/pickup-contacts",f.parent(),body).path("id")).isEqualTo(row.path("id"));
        var bad=contactBody(f,"虚构拒绝字段"); bad.put("idCardNumber","not-collected"); call(HttpMethod.POST,"/pickup-contacts",f.parent(),bad,400);
        var update=edit(row,"虚构常用接送人"); update.put("identity_card",Map.of("front","not-collected")); call(HttpMethod.PATCH,"/pickup-contacts/"+row.path("id"),f.parent(),update,400);
        var authorization=new LinkedHashMap<String,Object>(Map.of("studentId",f.student(),"contactId",row.path("id").asLong(),"expectedContactVersion",row.path("version").asInt(),"date",today,"requestId",key(),"faceStatus","VERIFIED"));
        var created=post("/pickups",f.parent(),authorization); assertThat(created.path("pickupCode").asText()).matches("[0-9]{6}");
        assertThat(created.path("faceStatus").asText()).isEqualTo("NOT_CONFIGURED"); assertThat(created.path("mode").asText()).isEqualTo("SINGLE");
        var replay=post("/pickups",f.parent(),authorization); assertThat(replay.path("id")).isEqualTo(created.path("id")); assertThat(replay.has("pickupCode")).isFalse();
        assertThat(replay.has("codeHash")).isFalse();
        authorization.put("idNumber","not-collected"); call(HttpMethod.POST,"/pickups",f.parent(),authorization,400);
        assertThat(attendance(f,false).path("success").asBoolean()).isTrue();
        verify(f,created,created.path("pickupCode").asText()); assertThat(attendance(f,true).path("success").asBoolean()).isTrue();
        assertThat(current(created,f).path("status").asText()).isEqualTo("USED");
    }
    @Test void overlapChecksCompareBothDateRangesAndDailyWindowsWithoutBlockingContactLists() {
        var f=fixture(); var first=contact(f,"虚构早间接送人"); var second=contact(f,"虚构晚间接送人");
        String tomorrow=sec.today().plusDays(1).toString(),after=sec.today().plusDays(2).toString();
        post("/pickups",f.parent(),authorize(f,first,"MULTI",today,tomorrow,"09:00","11:00"));
        post("/pickups",f.parent(),authorize(f,second,"SINGLE",today,today,"11:00","12:00"));
        call(HttpMethod.POST,"/pickups",f.parent(),authorize(f,second,"SINGLE",tomorrow,tomorrow,"10:00","10:30"),409);
        post("/pickups",f.parent(),authorize(f,first,"MULTI",after,after,"09:00","11:00"));
        assertThat(get("/pickup-contacts?studentId="+f.student(),f.parent()).size()).isEqualTo(2);
        assertThat(get("/pickups?studentId="+f.student(),f.parent()).size()).isEqualTo(3);
        assertThat(get("/pickups?studentId="+f.student()+"&date="+tomorrow,f.parent()).size()).isEqualTo(1);
        call(HttpMethod.POST,"/pickups",f.parent(),authorize(f,first,"SINGLE",today,tomorrow,"12:00","13:00"),400);
        call(HttpMethod.POST,"/pickups",f.parent(),authorize(f,first,"MULTI",today,tomorrow,"22:00","06:00"),400);
    }
    @Test void multiAuthorizationConsumesOncePerDayAndAccountsRemainCorrectUnderConcurrentCheckout() {
        var f=fixture(); long account=fundTwoDays(f); var contact=contact(f,"虚构多日接送人");
        var row=post("/pickups",f.parent(),authorize(f,contact,"MULTI",today,sec.today().plusDays(1).toString(),"09:00","21:00")); String code=row.path("pickupCode").asText();
        assertThat(attendance(f,false).path("success").asBoolean()).isTrue(); verify(f,row,code); concurrentCheckout(f);
        var dayOne=current(row,f); assertThat(dayOne.path("status").asText()).isEqualTo("ACTIVE"); assertThat(dayOne.path("usedToday").asBoolean()).isTrue(); assertThat(dayOne.path("usageDates").size()).isEqualTo(1);
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("3");
        clock.instant.set(clock.instant().plus(Duration.ofDays(1))); f=refreshTokens(f); boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText();
        assertThat(attendance(f,false).path("success").asBoolean()).isTrue(); var dayTwo=current(row,f);
        assertThat(dayTwo.path("usedToday").asBoolean()).isFalse(); assertThat(dayTwo.path("canCheckout").asBoolean()).isFalse();
        verify(f,dayTwo,code); concurrentCheckout(f);
        var finished=current(row,f); assertThat(finished.path("usageDates").size()).isEqualTo(2); assertThat(finished.path("canVerify").asBoolean()).isFalse();
        assertThat(account(f,account).path("balance").decimalValue()).isEqualByComparingTo("1"); assertThat(account(f,account).path("consumed").decimalValue()).isEqualByComparingTo("4");
        long consumptions=0; for(var entry:get("/lesson-ledger?accountId="+account,boss)) if(entry.path("type").asText().equals("CONSUMPTION")) consumptions++;
        assertThat(consumptions).isEqualTo(2);
    }
    @Test void anUnusedVerificationCannotCrossMidnightEvenWhenItsExpiryIsExtended() {
        var f=fixture(); var contact=contact(f,"虚构跨日接送人"); var row=post("/pickups",f.parent(),authorize(f,contact,"MULTI",today,sec.today().plusDays(1).toString(),"09:00","21:00")); String code=row.path("pickupCode").asText();
        attendance(f,false); var verification=verify(f,row,code).path("verification");
        db.update("UPDATE pickup_verification SET expires_at=? WHERE id=?",sec.now().plusDays(2),verification.path("id").asLong());
        clock.instant.set(clock.instant().plus(Duration.ofDays(1))); f=refreshTokens(f); attendance(f,false);
        assertThat(current(row,f).path("canCheckout").asBoolean()).isFalse(); assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
        verify(f,current(row,f),code); assertThat(attendance(f,true).path("success").asBoolean()).isTrue();
    }
    @Test void contactChangesDeletionAndGuardianUnbindingInvalidatePreviouslyVerifiedCheckout() {
        var f=fixture(); var contact=contact(f,"虚构变更接送人"); var row=post("/pickups",f.parent(),authorize(f,contact,"MULTI",today,sec.today().plusDays(1).toString(),"09:00","21:00"));
        attendance(f,false); verify(f,row,row.path("pickupCode").asText());
        var changed=call(HttpMethod.PATCH,"/pickup-contacts/"+contact.path("id"),f.parent(),edit(contact,"虚构已修改接送人"),200);
        call(HttpMethod.POST,"/pickups",f.parent(),authorize(f,contact,"SINGLE",today,today,"09:00","21:00"),409);
        assertThat(current(row,f).path("status").asText()).isEqualTo("REVOKED"); assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
        var replacement=post("/pickups",f.parent(),authorize(f,changed,"SINGLE",today,today,"09:00","21:00")); verify(f,replacement,replacement.path("pickupCode").asText());
        call(HttpMethod.DELETE,"/pickup-contacts/"+changed.path("id"),f.parent(),Map.of("expectedVersion",changed.path("version").asInt()),200);
        assertThat(attendance(f,true).path("success").asBoolean()).isFalse(); assertThat(current(replacement,f).path("canCheckout").asBoolean()).isFalse();
        var other=fixture(); var otherContact=contact(other,"虚构解绑接送人"); var authorization=post("/pickups",other.parent(),authorize(other,otherContact,"MULTI",today,sec.today().plusDays(1).toString(),"09:00","21:00")); attendance(other,false); verify(other,authorization,authorization.path("pickupCode").asText());
        call(HttpMethod.PUT,"/users/"+other.guardian()+"/access",boss,Map.of("studentIds",List.of()),200);
        assertThat(attendance(other,true).path("success").asBoolean()).isFalse(); assertThat(current(authorization,other).path("canVerify").asBoolean()).isFalse();
    }
    @Test void privacyExportIncludesContactsAndErasureClosesTheirFutureAccess() {
        var f=fixture(); contact(f,"虚构隐私接送人");
        var request=post("/privacy/requests",f.parent(),Map.of("studentId",f.student(),"type","EXPORT","reason","虚构资料副本验收"));
        post("/privacy/requests/"+request.path("id")+"/review",boss,Map.of("decision","APPROVE","note","虚构身份核对完成"));
        var exported=post("/privacy/requests/"+request.path("id")+"/export",f.parent(),Map.of());
        assertThat(exported.path("pickupContacts").get(0).path("name").asText()).isEqualTo("虚构隐私接送人"); assertThat(exported.path("pickupUsage").isArray()).isTrue(); assertThat(exported.toString()).doesNotContain("requestHash","codeHash");
        var erase=post("/privacy/requests",f.parent(),Map.of("studentId",f.student(),"type","ERASURE","reason","虚构资料脱敏验收"));
        post("/privacy/requests/"+erase.path("id")+"/review",boss,Map.of("decision","APPROVE","note","已核对离校与账目"));
        assertThat(post("/privacy/requests/"+erase.path("id")+"/erase",boss,Map.of("confirmed",true,"note","已完成最终核对")).path("status").asText()).isEqualTo("COMPLETED");
        call(HttpMethod.GET,"/pickup-contacts?studentId="+f.student(),boss,null,409);
    }
}
