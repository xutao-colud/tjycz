package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:pickupalerts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/pickup-alert-uploads","app.pickup-face-provider="})
@ActiveProfiles("local")
@Import(PickupAlertIntegrationTest.TimeConfig.class)
class PickupAlertIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary Clock alertClock() { return Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"),Application.ZONE); } }
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String boss,today;
    @BeforeEach void prepare() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText(); today=sec.today().toString();
    }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode call(HttpMethod method,String path,String actor,Object body,int status) {
        if(method==HttpMethod.POST && status==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,sec,body);
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(actor!=null) headers.setBearerAuth(actor);
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path).isEqualTo(status);
        try { return json.readTree(response.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode post(String path,String actor,Object body) { return call(HttpMethod.POST,path,actor,body,200); }
    JsonNode get(String path,String actor) { return call(HttpMethod.GET,path,actor,null,200); }
    String token(long id) { return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString(); }
    record Fixture(long student,long classroom,String parent,String teacher) {}
    Fixture fixture() {
        long classroom=post("/classes",boss,Map.of("campusId",1,"name","虚构接送异常班-"+key(),"teacherName","虚构异常老师")).path("id").asLong();
        long student=post("/students",boss,Map.of("name","虚构异常验收学生","classId",classroom,"gender","男","grade","三年级","parentName","虚构监护人","parentPhone","13800001234")).path("id").asLong();
        long parent=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'PARENT')",key(),"fixture-unused-hash","虚构监护人");
        long teacher=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'TEACHER')",key(),"fixture-unused-hash","虚构异常老师");
        db.update("INSERT INTO parent_student VALUES(?,?)",parent,student); db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,classroom);
        return new Fixture(student,classroom,token(parent),token(teacher));
    }
    Map<String,Object> manualBody(Fixture f) { return new LinkedHashMap<>(Map.of("studentId",f.student(),"date",today,"pickupName","虚构现场来访者","phoneLast4","9876","reason","虚构异常：接送安排与监护人说明不一致","requestId",key())); }
    JsonNode manual(Fixture f) { return post("/pickup-alerts",f.teacher(),manualBody(f)); }
    JsonNode handle(Fixture f,JsonNode alert,String action) { return post("/pickup-alerts/"+alert.path("id")+"/handle",f.teacher(),Map.of("expectedVersion",alert.path("version").asInt(),"action",action,"note","虚构处理说明：已联系监护人完成核对")); }
    JsonNode alerts(Fixture f) { return get("/pickup-alerts?studentId="+f.student()+"&date="+today,f.teacher()); }
    JsonNode alert(Fixture f,JsonNode row) { return get("/pickup-alerts/"+row.path("id"),f.teacher()); }
    JsonNode find(Fixture f,long authorization,String source) { for(var row:alerts(f)) if(row.path("authorizationId").asLong()==authorization&&row.path("source").asText().equals(source)) return row; throw new AssertionError("虚构告警不存在"); }
    JsonNode authorization(Fixture f,String start,String end) { return post("/pickups",f.parent(),Map.of("studentId",f.student(),"date",today,"pickupName","虚构接送人","relationship","亲属","phone","13800001234","validFrom",today+"T"+start+":00","expiresAt",today+"T"+end+":00","requestId",key())); }
    JsonNode current(Fixture f,JsonNode authorization) { return get("/pickups/"+authorization.path("id"),f.teacher()); }
    Map<String,Object> evidence(JsonNode row,String code) { return Map.of("pickupName","虚构接送人","phoneLast4","1234","code",code,"confirmedIdentity",true,"expectedVersion",row.path("version").asInt(),"requestId",key()); }
    JsonNode verify(Fixture f,JsonNode row,String code,int status) { return call(HttpMethod.POST,"/pickups/"+row.path("id")+"/verify",f.teacher(),evidence(row,code),status); }
    String wrong(String code) { return code.equals("000000")?"000001":"000000"; }
    JsonNode attendance(Fixture f,boolean checkout) { return post(checkout?"/attendance/check-out":"/attendance/check-in",f.teacher(),Map.of("studentIds",List.of(f.student()),"date",today)).path("results").get(0); }
    List<String> actions(JsonNode row) { var values=new ArrayList<String>(); for(var log:row.path("logs")) values.add(log.path("action").asText()); return values; }

    @Test void alertsEnforceStaffParentAndClassBindingsWithoutExposingInternalKeys() {
        var a=fixture(); var b=fixture(); var row=manual(a); String path="/pickup-alerts/"+row.path("id");
        assertThat(row.path("source").asText()).isEqualTo("MANUAL"); assertThat(row.path("notificationStatus").asText()).isEqualTo("IN_APP"); assertThat(row.path("canHandle").asBoolean()).isTrue();
        assertThat(row.has("requestHash")).isFalse(); assertThat(row.has("sourceKey")).isFalse();
        var parent=get(path,a.parent()); assertThat(parent.has("parentPhone")).isFalse(); assertThat(parent.path("canHandle").asBoolean()).isFalse();
        assertThat(get("/pickup-alerts?studentId="+a.student(),a.parent()).size()).isEqualTo(1);
        call(HttpMethod.POST,"/pickup-alerts",a.parent(),manualBody(a),403);
        call(HttpMethod.POST,path+"/handle",a.parent(),Map.of("action","RESOLVED","note","越权处理","expectedVersion",0),403);
        for(String outsider:List.of(b.parent(),b.teacher())) {
            call(HttpMethod.GET,path,outsider,null,403); call(HttpMethod.GET,"/pickup-alerts?studentId="+a.student(),outsider,null,403);
            call(HttpMethod.POST,"/pickup-alerts",outsider,manualBody(a),403);
            call(HttpMethod.POST,path+"/handle",outsider,Map.of("action","HOLD","note","越权处理","expectedVersion",0),403);
        }
        assertThat(get("/pickup-alerts",b.teacher()).size()).isZero();
    }
    @Test void manualRequestsAreIdempotentAndRejectConflictsInvalidDatesAndInvalidLastFourDigits() {
        var f=fixture(); var body=manualBody(f); var first=post("/pickup-alerts",f.teacher(),body); var replay=post("/pickup-alerts",f.teacher(),body);
        assertThat(replay.path("id")).isEqualTo(first.path("id")); assertThat(replay.path("logs").size()).isEqualTo(1); assertThat(alerts(f).size()).isEqualTo(1);
        body.put("reason","虚构变化后的异常原因"); call(HttpMethod.POST,"/pickup-alerts",f.teacher(),body,409);
        for(String date:List.of(sec.today().minusDays(1).toString(),sec.today().plusDays(1).toString())) { var invalid=manualBody(f); invalid.put("date",date); call(HttpMethod.POST,"/pickup-alerts",f.teacher(),invalid,400); }
        for(String last4:List.of("123","12345","abcd")) { var invalid=manualBody(f); invalid.put("phoneLast4",last4); call(HttpMethod.POST,"/pickup-alerts",f.teacher(),invalid,400); }
        var unknown=manualBody(f); unknown.put("phoneLast4",""); assertThat(post("/pickup-alerts",f.teacher(),unknown).path("phoneLast4").asText()).isEmpty();
    }
    @Test void holdAndResolveRequireFreshVersionsAndPreserveTheCompleteHandlingLog() {
        var f=fixture(); var opened=manual(f); var held=handle(f,opened,"HOLD"); String path="/pickup-alerts/"+opened.path("id")+"/handle";
        assertThat(held.path("status").asText()).isEqualTo("HOLD"); assertThat(held.path("version").asInt()).isEqualTo(opened.path("version").asInt()+1);
        call(HttpMethod.POST,path,f.teacher(),Map.of("action","RESOLVED","note","使用过期版本","expectedVersion",opened.path("version").asInt()),409);
        call(HttpMethod.POST,path,f.teacher(),Map.of("action","RESOLVED","note"," ","expectedVersion",held.path("version").asInt()),400);
        call(HttpMethod.POST,path,f.teacher(),Map.of("action","OPEN","note","不支持直接覆盖状态","expectedVersion",held.path("version").asInt()),400);
        var resolved=handle(f,held,"RESOLVED"); assertThat(resolved.path("canHandle").asBoolean()).isFalse(); assertThat(actions(resolved)).containsExactly("OPEN","HOLD","RESOLVED");
        call(HttpMethod.POST,path,f.teacher(),Map.of("action","HOLD","note","不能覆盖已经完成的记录","expectedVersion",resolved.path("version").asInt()),400);
    }
    @Test void aManualAlertInvalidatesExistingVerificationAndResolvingItNeverGrantsCheckout() {
        var f=fixture(); attendance(f,false); var authorization=authorization(f,"09:00","21:00"); String code=authorization.path("pickupCode").asText(); verify(f,authorization,code,200);
        assertThat(current(f,authorization).path("canCheckout").asBoolean()).isTrue();
        var opened=manual(f); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse(); assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
        handle(f,opened,"RESOLVED");
        assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse(); assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
        verify(f,current(f,authorization),code,200); assertThat(attendance(f,true).path("success").asBoolean()).isTrue();
    }
    @Test void placingAnAlertOnHoldInvalidatesANewerVerificationUntilItIsResolvedAndRechecked() {
        var f=fixture(); attendance(f,false); var authorization=authorization(f,"09:00","21:00"); String code=authorization.path("pickupCode").asText(); var opened=manual(f);
        verify(f,authorization,code,200);
        var held=handle(f,opened,"HOLD"); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse();
        handle(f,held,"RESOLVED"); assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
        verify(f,current(f,authorization),code,200); assertThat(attendance(f,true).path("success").asBoolean()).isTrue();
    }
    @Test void successfulVerificationOnlyClearsMatchingAutomaticOpenAlertsAndNeverManualOrHoldAlerts() {
        var f=fixture(); attendance(f,false); var authorization=authorization(f,"09:00","12:00"); var future=authorization(f,"13:00","21:00"); String code=authorization.path("pickupCode").asText(); long id=authorization.path("id").asLong();
        verify(f,authorization,wrong(code),400); var own=find(f,id,"VERIFICATION"); assertThat(own.path("status").asText()).isEqualTo("OPEN");
        verify(f,current(f,authorization),code,200); assertThat(alert(f,own).path("status").asText()).isEqualTo("RESOLVED");
        verify(f,future,future.path("pickupCode").asText(),400); var foreign=find(f,future.path("id").asLong(),"VERIFICATION"); var manual=manual(f);
        verify(f,current(f,authorization),wrong(code),400); var reopened=alert(f,own); var held=handle(f,reopened,"HOLD");
        verify(f,current(f,authorization),code,200);
        assertThat(alert(f,held).path("status").asText()).isEqualTo("HOLD"); assertThat(alert(f,foreign).path("status").asText()).isEqualTo("OPEN"); assertThat(alert(f,manual).path("status").asText()).isEqualTo("OPEN");
        assertThat(attendance(f,true).path("success").asBoolean()).isFalse();
    }
    @Test void repeatedVerificationFailuresMergeAndReopenTheSameAlertWithoutDuplicatingTheIncident() {
        var f=fixture(); attendance(f,false); var authorization=authorization(f,"09:00","21:00"); String code=authorization.path("pickupCode").asText();
        verify(f,authorization,wrong(code),400); var opened=alerts(f).get(0); verify(f,current(f,authorization),wrong(code),400);
        assertThat(alerts(f).size()).isEqualTo(1); assertThat(alert(f,opened).path("logs").size()).isEqualTo(2);
        verify(f,current(f,authorization),code,200); assertThat(alert(f,opened).path("status").asText()).isEqualTo("RESOLVED"); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isTrue();
        verify(f,current(f,authorization),wrong(code),400); var reopened=alerts(f).get(0);
        assertThat(alerts(f).size()).isEqualTo(1); assertThat(reopened.path("id")).isEqualTo(opened.path("id")); assertThat(reopened.path("status").asText()).isEqualTo("OPEN");
        assertThat(actions(reopened)).containsExactly("REJECTED","REJECTED","VERIFIED","REJECTED"); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse();
    }
    @Test void disabledFaceVerificationReturns410WithoutSavingFilesOrPretendingVerificationSucceeded() {
        var f=fixture(); var outsider=fixture(); var authorization=authorization(f,"09:00","21:00");
        call(HttpMethod.GET,"/pickup-face/status",null,null,401);
        var status=get("/pickup-face/status",f.parent()); assertThat(status.path("providerConfigured").asBoolean()).isFalse(); assertThat(status.path("captureEnabled").asBoolean()).isFalse(); assertThat(status.path("manualVerificationAvailable").asBoolean()).isTrue(); assertThat(status.path("status").asText()).isEqualTo("DISABLED");
        long files=db.count("SELECT COUNT(*) FROM stored_file"),verifications=db.count("SELECT COUNT(*) FROM pickup_verification WHERE student_id=?",f.student());
        var response=call(HttpMethod.POST,"/pickup-face/verify",f.parent(),Map.of("studentId",f.student(),"pickupName","虚构接送人","photoBase64","fiction-only-unused"),410);
        assertThat(response.path("status").asText()).isEqualTo("DISABLED"); assertThat(response.path("verified").asBoolean()).isFalse(); assertThat(response.path("code").asText()).isEqualTo("FACE_VERIFICATION_DISABLED");
        assertThat(db.count("SELECT COUNT(*) FROM stored_file")).isEqualTo(files); assertThat(db.count("SELECT COUNT(*) FROM pickup_verification WHERE student_id=?",f.student())).isEqualTo(verifications);
        assertThat(current(f,authorization).path("faceStatus").asText()).isEqualTo("NOT_CONFIGURED"); assertThat(current(f,authorization).path("canCheckout").asBoolean()).isFalse();
        call(HttpMethod.POST,"/pickup-face/verify",outsider.teacher(),Map.of("studentId",f.student()),403);
    }
    @Test void unresolvedOpenOrHoldAlertsBlockErasureUntilTheirHandlingIsComplete() {
        var f=fixture(); var opened=manual(f);
        var request=post("/privacy/requests",f.parent(),Map.of("studentId",f.student(),"type","ERASURE","reason","虚构验收：离校资料脱敏"));
        String path="/privacy/requests/"+request.path("id"); post(path+"/review",boss,Map.of("decision","APPROVE","note","虚构身份与资料范围已核对"));
        assertThat(get(path+"/erasure-check",boss).path("blocks").toString()).contains("未处理接送异常");
        call(HttpMethod.POST,path+"/erase",boss,Map.of("confirmed",true,"note","虚构最终核对"),409);
        var held=handle(f,opened,"HOLD"); assertThat(get(path+"/erasure-check",boss).path("blocks").toString()).contains("未处理接送异常");
        handle(f,held,"RESOLVED"); assertThat(get(path+"/erasure-check",boss).path("blocks").size()).isZero();
        assertThat(post(path+"/erase",boss,Map.of("confirmed",true,"note","虚构最终核对完成")).path("status").asText()).isEqualTo("COMPLETED");
        call(HttpMethod.GET,"/pickup-alerts/"+opened.path("id"),boss,null,409);
    }
    @Test void growthPickupSummaryAndWorkbenchAlertsRemainWithinCurrentChildAndClassBindings() {
        var a=fixture(); var b=fixture();
        var contact=post("/pickup-contacts",a.parent(),Map.of("studentId",a.student(),"name","虚构接送人","relationship","亲属","phone","13800001234","isDefault",true,"requestId",key()));
        post("/pickup-contacts",a.parent(),Map.of("studentId",a.student(),"name","虚构备用接送人","relationship","亲属","phone","13800009999","isDefault",false,"requestId",key()));
        post("/pickup-contacts",b.parent(),Map.of("studentId",b.student(),"name","虚构另一班接送人","relationship","亲属","phone","13800008888","requestId",key()));
        long course=post("/courses",boss,Map.of("campusId",1,"name","虚构接送预计时间课程-"+key(),"type","EVENING","unit","次","price",1000,"warningThreshold",1)).path("id").asLong();
        post("/enrollments",boss,Map.of("studentId",a.student(),"courseId",course));
        post("/schedules",boss,Map.of("classId",a.classroom(),"courseId",course,"date",today,"startTime","16:00","endTime","18:30","units",1));
        attendance(a,false);
        var authorization=post("/pickups",a.parent(),Map.of("studentId",a.student(),"contactId",contact.path("id").asLong(),"expectedContactVersion",contact.path("version").asInt(),"date",today,"requestId",key()));
        verify(a,authorization,authorization.path("pickupCode").asText(),200);
        assertThat(current(a,authorization).path("canCheckout").asBoolean()).isTrue();
        var own=manual(a); var completed=manual(a); handle(a,completed,"RESOLVED"); var other=manual(b);
        assertThat(current(a,authorization).path("canCheckout").asBoolean()).isFalse();
        var growth=get("/growth?studentId="+a.student()+"&date="+today,a.parent()); var pickup=growth.path("pickup");
        assertThat(growth.path("student").path("id").asLong()).isEqualTo(a.student());
        assertThat(pickup.path("defaultContact").path("id")).isEqualTo(contact.path("id"));
        assertThat(pickup.path("defaultContact").path("name").asText()).isEqualTo("虚构接送人");
        assertThat(pickup.path("defaultContact").has("phone")).isFalse();
        assertThat(pickup.path("planned").path("id")).isEqualTo(authorization.path("id"));
        assertThat(pickup.path("planned").path("mode").asText()).isEqualTo("SINGLE");
        assertThat(pickup.path("openAlertCount").asInt()).isEqualTo(1);
        assertThat(pickup.path("expectedCheckout").asText()).startsWith("18:30");
        assertThat(pickup.toString()).doesNotContain("虚构另一班接送人");
        call(HttpMethod.GET,"/growth?studentId="+b.student()+"&date="+today,a.parent(),null,403);
        call(HttpMethod.GET,"/growth?studentId="+a.student()+"&date="+today,b.parent(),null,403);
        var workbench=get("/workbench?classId="+a.classroom()+"&date="+today,a.teacher());
        assertThat(workbench.path("pickupAlerts").size()).isEqualTo(1);
        assertThat(workbench.path("pickupAlerts").get(0).path("id")).isEqualTo(own.path("id"));
        assertThat(workbench.path("pickupAlerts").get(0).path("studentId").asLong()).isEqualTo(a.student());
        var otherWorkbench=get("/workbench?classId="+b.classroom()+"&date="+today,b.teacher());
        assertThat(otherWorkbench.path("pickupAlerts").size()).isEqualTo(1);
        assertThat(otherWorkbench.path("pickupAlerts").get(0).path("id")).isEqualTo(other.path("id"));
        call(HttpMethod.GET,"/workbench?classId="+b.classroom()+"&date="+today,a.teacher(),null,403);
        call(HttpMethod.GET,"/workbench?classId="+a.classroom()+"&date="+today,a.parent(),null,403);
    }
}
