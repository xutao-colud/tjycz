package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true",
    "spring.datasource.url=jdbc:h2:mem:privacy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/privacy-uploads"})
@ActiveProfiles("local")
class PrivacyIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String boss,parent,other,teacher,version; long campus,student,second,parentId,otherId,teacherId,cl;
    JsonNode call(HttpMethod method,String path,String token,Object body,int expected) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) { headers.setBearerAuth(token); if(campus>0) headers.set("X-Campus-Id",Long.toString(campus)); }
        var result=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(result.getStatusCode().value()).as(method+" "+path+" "+result.getBody()).isEqualTo(expected);
        try { return result.getBody()==null?json.nullNode():json.readTree(result.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode get(String path,String token) { return call(HttpMethod.GET,path,token,null,200); }
    JsonNode post(String path,String token,Object body) { return call(HttpMethod.POST,path,token,body,200); }
    long user(String role) { return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"unused-fixture-hash","隐私测试"+role,role); }
    String token(long user) { return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",user),false).get("token").toString(); }
    @BeforeEach void setup() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText();
        campus=post("/campuses",boss,Map.of("name","隐私验收"+UUID.randomUUID())).path("id").asLong();
        cl=post("/classes",boss,Map.of("name","隐私验收班","teacherName","测试老师")).path("id").asLong();
        student=post("/students",boss,Map.of("name","隐私甲孩子","gender","男","grade","三年级","classId",cl,"parentName","监护人甲","parentPhone","13800000000")).path("id").asLong();
        second=post("/students",boss,Map.of("name","隐私乙孩子","gender","女","grade","三年级","classId",cl,"parentName","监护人乙","parentPhone","13800000001")).path("id").asLong();
        parentId=user("PARENT"); otherId=user("PARENT"); teacherId=user("TEACHER");
        db.update("INSERT INTO parent_student VALUES(?,?)",parentId,student); db.update("INSERT INTO parent_student VALUES(?,?)",otherId,second); db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,cl);
        parent=token(parentId); other=token(otherId); teacher=token(teacherId);
        version=get("/privacy/policy",parent).path("version").asText();
    }
    void grant(String token,long student) { for(String scope:List.of("SERVICE","MEDIA")) post("/privacy/consents",token,Map.of("studentId",student,"scope",scope,"policyVersion",version,"granted",true)); }
    JsonNode upload(String token) {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aUe8AAAAASUVORK5CYII=");
        HttpHeaders headers=new HttpHeaders(); headers.setBearerAuth(token); headers.set("X-Campus-Id",Long.toString(campus)); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String,Object> body=new LinkedMultiValueMap<>(); body.add("file",new ByteArrayResource(png){ @Override public String getFilename(){return "隐私验收.png";} });
        var response=http.exchange("/api/files",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200); return response.getBody();
    }
    long homework(long student) {
        long task=post("/homework/tasks",teacher,Map.of("date",sec.today().toString(),"subject","语文","type","WRITTEN","title","隐私验收作业","studentIds",List.of(student))).path("taskId").asLong();
        for(var row:get("/homework?studentId="+student,teacher)) if(row.path("taskId").asLong()==task) return row.path("id").asLong();
        throw new AssertionError("缺少作业");
    }
    @Test void explicitGuardianConsentProtectsLinksAndEveryTicketDownload() {
        assertThat(db.count("SELECT COUNT(*) FROM privacy_consent WHERE student_id=?",student)).isZero();
        var file=upload(parent); long id=file.path("id").asLong(),homework=homework(student);
        String url=file.path("url").asText(),ticket=url.substring(url.indexOf("ticket=")+7);
        assertThat(url).doesNotContain(parent).doesNotContain("?token=").contains("?ticket=");
        assertThat(http.getForEntity(url,byte[].class).getStatusCode().value()).isEqualTo(200);
        call(HttpMethod.GET,"/me",ticket,null,401);
        var otherFile=upload(other);
        assertThat(http.getForEntity("/api/files/"+otherFile.path("id").asLong()+"?ticket="+ticket,byte[].class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/api/files/"+id+"?token="+parent,byte[].class).getStatusCode().value()).isEqualTo(401);
        call(HttpMethod.POST,"/homework/"+homework+"/attachments",parent,Map.of("fileIds",List.of(id)),403);
        call(HttpMethod.POST,"/privacy/consents",other,Map.of("studentId",student,"scope","MEDIA","policyVersion",version,"granted",true),403);
        call(HttpMethod.POST,"/privacy/consents",teacher,Map.of("studentId",student,"scope","MEDIA","policyVersion",version,"granted",true),403);
        call(HttpMethod.POST,"/privacy/consents",parent,Map.of("studentId",student,"scope","MEDIA","policyVersion","outdated","granted",true),409);
        grant(parent,student); post("/homework/"+homework+"/attachments",parent,Map.of("fileIds",List.of(id)));
        String linkedUrl=get("/files/"+id+"/access",parent).path("url").asText();
        post("/privacy/consents",parent,Map.of("studentId",student,"scope","MEDIA","policyVersion",version,"granted",false));
        assertThat(http.getForEntity(linkedUrl,byte[].class).getStatusCode().value()).isEqualTo(403);
        call(HttpMethod.GET,"/files/"+id+"/access",parent,null,403);
        assertThat(get("/homework/"+homework,parent).path("attachments")).isEmpty();
        grant(parent,student);
        String renewed=get("/files/"+id+"/access",parent).path("url").asText();
        assertThat(http.getForEntity(renewed,byte[].class).getStatusCode().value()).isEqualTo(200);
        call(HttpMethod.PUT,"/users/"+parentId+"/access",boss,Map.of("studentIds",List.of()),200);
        assertThat(http.getForEntity(renewed,byte[].class).getStatusCode().value()).isEqualTo(403);
    }
    @Test void oneGuardiansWithdrawalCannotBeOverriddenByAnotherOldConsent() {
        db.update("INSERT INTO parent_student VALUES(?,?)",otherId,student);
        grant(parent,student); grant(other,student);
        var file=upload(parent); long homework=homework(student);
        post("/homework/"+homework+"/attachments",parent,Map.of("fileIds",List.of(file.path("id").asLong())));
        post("/privacy/consents",parent,Map.of("studentId",student,"scope","MEDIA","policyVersion",version,"granted",false));
        assertThat(get("/privacy/consents?studentId="+student,other).path("effectiveMedia").asBoolean()).isFalse();
        call(HttpMethod.GET,"/files/"+file.path("id").asLong()+"/access",other,null,403);
        grant(parent,student);
        assertThat(get("/privacy/consents?studentId="+student,other).path("effectiveMedia").asBoolean()).isTrue();
    }
    @Test void sharedMediaRequiresEveryLinkedChildsConsentAndCurrentReaderAccess() {
        grant(parent,student); grant(other,second);
        var file=upload(teacher); long id=file.path("id").asLong(),firstHomework=homework(student),secondHomework=homework(second);
        post("/homework/"+firstHomework+"/attachments",teacher,Map.of("fileIds",List.of(id)));
        post("/homework/"+secondHomework+"/attachments",teacher,Map.of("fileIds",List.of(id)));
        String url=get("/files/"+id+"/access",other).path("url").asText();
        assertThat(http.getForEntity(url,byte[].class).getStatusCode().value()).isEqualTo(200);
        post("/privacy/consents",parent,Map.of("studentId",student,"scope","MEDIA","policyVersion",version,"granted",false));
        assertThat(http.getForEntity(url,byte[].class).getStatusCode().value()).isEqualTo(403);
        call(HttpMethod.GET,"/files/"+id+"/access",teacher,null,403);
        call(HttpMethod.GET,"/files/"+id+"/access",other,null,403);
        assertThat(get("/homework/"+secondHomework,other).path("attachments")).isEmpty();
        assertThat(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=?",id)).isEqualTo(2);
        grant(parent,student);
        assertThat(http.getForEntity(get("/files/"+id+"/access",other).path("url").asText(),byte[].class).getStatusCode().value()).isEqualTo(200);
    }
    @Test void ticketsExpireAndLogoutRevokesSessionAndTicketsWithoutLeakingSecretsIntoAudit() {
        var file=upload(parent); long id=file.path("id").asLong();
        db.update("UPDATE file_access_ticket SET expires_at=? WHERE file_id=?",sec.now().minusSeconds(1),id);
        assertThat(http.getForEntity(file.path("url").asText(),byte[].class).getStatusCode().value()).isEqualTo(401);
        String renewed=get("/files/"+id+"/access",parent).path("url").asText();
        assertThat(http.getForEntity(renewed,byte[].class).getStatusCode().value()).isEqualTo(200);
        get("/students",teacher); call(HttpMethod.GET,"/students/"+student,other,null,403);
        var challenge=get("/auth/challenge",null);
        Map<String,Object> encrypted=PasswordTransportTestClient.encrypt(json.convertValue(challenge,new com.fasterxml.jackson.core.type.TypeReference<>(){}),"LOGIN",Map.of("username","nonexistent-privacy-account","password","NeverLogThisFixturePassword!"));
        call(HttpMethod.POST,"/auth/login",null,encrypted,401);
        post("/auth/logout",parent,Map.of());
        call(HttpMethod.GET,"/me",parent,null,401);
        assertThat(http.getForEntity(renewed,byte[].class).getStatusCode().value()).isEqualTo(401);
        assertThat(db.count("SELECT COUNT(*) FROM app_session WHERE token_hash=?",sec.hash(parent))).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM file_access_ticket WHERE session_hash=?",sec.hash(parent))).isZero();
        for(String action:List.of("LOGIN_FAILED","ACCESS_DENIED","SENSITIVE_READ","FILE_UPLOAD","FILE_READ","LOGOUT")) assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action=?",action)).as(action).isPositive();
        for(var row:db.list("SELECT detail FROM audit_log")) assertThat(row.get("detail").toString()).doesNotContain(parent).doesNotContain(teacher).doesNotContain("NeverLogThisFixturePassword!").doesNotContain("ticket=");
    }
    @Test void unboundParentsKeepOnlyTheirOwnRequestReceiptAndCannotReadUpdatedChildData() {
        long request=post("/privacy/requests",parent,Map.of("studentId",student,"type","EXPORT","reason","原始申请说明")).path("id").asLong();
        post("/privacy/requests/"+request+"/review",boss,Map.of("decision","APPROVE","note","原审核说明"));
        post("/privacy/requests/"+request+"/export",parent,Map.of());
        call(HttpMethod.PUT,"/users/"+parentId+"/access",boss,Map.of("studentIds",List.of(second)),200);
        db.update("UPDATE student SET name='解绑后的新姓名' WHERE id=?",student);
        db.update("UPDATE privacy_request SET review_note='解绑后的新审核意见' WHERE id=?",request);
        var receipt=get("/privacy/requests/"+request,parent);
        assertThat(receipt.path("studentName").asText()).isEqualTo("已解除绑定的孩子");
        assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(receipt.path("type").asText()).isEqualTo("EXPORT");
        assertThat(receipt.path("canExport").asBoolean()).isFalse();
        for(String field:List.of("studentId","reason","reviewNote","exportJson")) assertThat(receipt.has(field)).as(field).isFalse();
        assertThat(get("/privacy/requests",parent).toString()).doesNotContain("解绑后的新姓名","解绑后的新审核意见","原始申请说明","隐私甲孩子");
        call(HttpMethod.POST,"/privacy/requests/"+request+"/export",parent,Map.of(),403);
        call(HttpMethod.GET,"/privacy/requests/"+request,other,null,403);
    }
    @Test void exportIsChildScopedAndErasureRequiresReviewConfirmationAndClearSafetyAndFinancialState() {
        grant(parent,student); grant(other,second);
        var shared=upload(teacher); var exclusive=upload(parent); long hs=homework(student),ho=homework(second);
        post("/homework/"+hs+"/attachments",parent,Map.of("fileIds",List.of(exclusive.path("id").asLong())));
        post("/homework/"+hs+"/attachments",teacher,Map.of("fileIds",List.of(shared.path("id").asLong())));
        post("/homework/"+ho+"/attachments",teacher,Map.of("fileIds",List.of(shared.path("id").asLong())));
        long export=post("/privacy/requests",parent,Map.of("studentId",student,"type","EXPORT","reason","获取孩子资料副本")).path("id").asLong();
        call(HttpMethod.POST,"/privacy/requests/"+export+"/export",parent,Map.of(),409);
        call(HttpMethod.POST,"/privacy/requests/"+export+"/review",teacher,Map.of("decision","APPROVE","note","不允许教师审批"),403);
        post("/privacy/requests/"+export+"/review",boss,Map.of("decision","APPROVE","note","已核对监护人身份"));
        for(int day=1;day<=3;day++) db.insert("INSERT INTO daily_summary(student_id,date,status,content,published_content) VALUES(?,?,'PUBLISHED',?,?)",student,sec.today().minusDays(day),"虚构导出正文".repeat(1800),"虚构导出正文".repeat(1800));
        var data=post("/privacy/requests/"+export+"/export",parent,Map.of());
        assertThat(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(65535);
        assertThat(data.path("student").path("id").asLong()).isEqualTo(student);
        assertThat(data.toString()).contains("隐私甲孩子").doesNotContain("隐私乙孩子").doesNotContain(parent).doesNotContain("storageKey");
        call(HttpMethod.POST,"/privacy/requests/"+export+"/export",other,Map.of(),403);
        long erasure=post("/privacy/requests",parent,Map.of("studentId",student,"type","ERASURE","reason","申请资料脱敏")).path("id").asLong();
        call(HttpMethod.POST,"/privacy/requests/"+erasure+"/erase",boss,Map.of("confirmed",true,"note","核对"),409);
        post("/privacy/requests/"+erasure+"/review",boss,Map.of("decision","APPROVE","note","已核对申请"));
        call(HttpMethod.POST,"/privacy/requests/"+erasure+"/erase",boss,Map.of("note","尚未确认"),400);
        db.update("INSERT INTO attendance(student_id,date,status,check_in_time,source) VALUES(?,?,'PRESENT',?,'MANUAL')",student,sec.today(),sec.now());
        call(HttpMethod.POST,"/privacy/requests/"+erasure+"/erase",boss,Map.of("confirmed",true,"note","在校时不得处理"),409);
        assertThat(db.one("SELECT name FROM student WHERE id=?",student).get("name")).isEqualTo("隐私甲孩子");
        db.update("UPDATE attendance SET status='LEFT',check_out_time=? WHERE student_id=?",sec.now(),student);
        var done=post("/privacy/requests/"+erasure+"/erase",boss,Map.of("confirmed",true,"note","已完成离校和费用核对"));
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(db.one("SELECT name,parent_phone FROM student WHERE id=?",student).get("name")).isEqualTo("已脱敏学生-"+student);
        assertThat(db.one("SELECT name FROM student WHERE id=?",second).get("name")).isEqualTo("隐私乙孩子");
        assertThat(db.count("SELECT COUNT(*) FROM file_link WHERE file_id=? AND student_id=?",shared.path("id").asLong(),second)).isEqualTo(1);
        assertThat(get("/files/"+shared.path("id").asLong()+"/access",other).path("url").asText()).contains("?ticket=");
        call(HttpMethod.GET,"/files/"+exclusive.path("id").asLong()+"/access",boss,null,403);
        assertThat(db.count("SELECT COUNT(*) FROM parent_student WHERE student_id=?",student)).isZero();
        assertThat(db.one("SELECT export_json FROM privacy_request WHERE id=?",export).get("exportJson")).isNull();
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='PRIVACY_ERASURE' AND actor_id=?",sec.authenticate(boss).id())).isEqualTo(1);
    }
    @Test void erasurePreservesFinancialHistoryAndBlocksPendingRenewalsBalancesAndRefunds() {
        long course=post("/courses",boss,Map.of("name","隐私财务验收课","type","EVENING","unit","次","price",100,"warningThreshold",0)).path("id").asLong();
        long account=post("/enrollments",boss,Map.of("studentId",student,"courseId",course)).path("accountId").asLong();
        long request=post("/privacy/requests",parent,Map.of("studentId",student,"type","ERASURE","reason","核对费用后脱敏")).path("id").asLong();
        post("/privacy/requests/"+request+"/review",boss,Map.of("decision","APPROVE","note","需先结清费用"));
        long renewal=post("/renewals",boss,Map.of("accountId",account,"amount",100,"lessons",1,"giftLessons",0,"paymentMethod","CASH","requestId",UUID.randomUUID().toString())).path("id").asLong();
        var pending=get("/privacy/requests/"+request+"/erasure-check",boss);
        assertThat(pending.path("blocks").toString()).contains("待确认续费");
        call(HttpMethod.POST,"/privacy/requests/"+request+"/erase",boss,Map.of("confirmed",true,"note","尚未结清"),409);
        post("/renewals/"+renewal+"/confirm",boss,Map.of());
        long refund=post("/refunds",parent,Map.of("renewalId",renewal,"amount",100,"lessons",1,"giftLessons",0,"reason","退款测试","requestId",UUID.randomUUID().toString())).path("id").asLong();
        var blocked=get("/privacy/requests/"+request+"/erasure-check",boss);
        assertThat(blocked.path("blocks").toString()).contains("课时余额未结清","退款尚未处理完成");
        call(HttpMethod.POST,"/privacy/requests/"+request+"/erase",boss,Map.of("confirmed",true,"note","尚未退款"),409);
        assertThat(db.one("SELECT name FROM student WHERE id=?",student).get("name")).isEqualTo("隐私甲孩子");
        assertThat(db.count("SELECT COUNT(*) FROM lesson_ledger WHERE account_id=?",account)).isEqualTo(1);
        post("/refunds/"+refund+"/review",boss,Map.of("approved",true,"note","费用核对完成","expectedVersion",0,"requestId",UUID.randomUUID().toString()));
        post("/refunds/"+refund+"/confirm",boss,Map.of("confirmed",true,"paymentMethod","CASH","paymentReference","包含监护信息的手工回执","note","完成退款","expectedVersion",1,"requestId",UUID.randomUUID().toString()));
        long ledgerId=Db.id(db.one("SELECT id FROM lesson_ledger WHERE account_id=? ORDER BY id LIMIT 1",account),"id"),actor=sec.authenticate(boss).id();
        long ownAudit=db.insert("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,'核对人',?,'LEDGER_REVERSE',?,?)",actor,campus,"ledger="+ledgerId+", reason=待清理的监护个人正文",sec.now());
        long otherAudit=db.insert("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,'核对人',?,'LEDGER_REVERSE',?,?)",actor,campus,"ledger="+ledgerId+"0, reason=他人的审计正文保留",sec.now());
        var financialBefore=db.list("SELECT type,amount,balance_before,balance_after FROM lesson_ledger WHERE account_id=? ORDER BY id",account);
        assertThat(get("/privacy/requests/"+request+"/erasure-check",boss).path("blocks")).isEmpty();
        post("/privacy/requests/"+request+"/erase",boss,Map.of("confirmed",true,"note","已完成退款与离校核对"));
        assertThat(db.count("SELECT COUNT(*) FROM lesson_ledger WHERE account_id=?",account)).isEqualTo(2);
        assertThat(db.list("SELECT type,amount,balance_before,balance_after FROM lesson_ledger WHERE account_id=? ORDER BY id",account)).isEqualTo(financialBefore);
        assertThat(db.one("SELECT detail,actor_id,action,created_at FROM audit_log WHERE id=?",ownAudit).get("detail")).isEqualTo("ledger="+ledgerId+", reason=已随资料脱敏");
        assertThat(db.one("SELECT detail FROM audit_log WHERE id=?",otherAudit).get("detail")).isEqualTo("ledger="+ledgerId+"0, reason=他人的审计正文保留");
        assertThat(Db.id(db.one("SELECT actor_id FROM audit_log WHERE id=?",ownAudit),"actorId")).isEqualTo(actor);
        assertThat(db.one("SELECT status,amount,payment_reference FROM refund_request WHERE id=?",refund).get("paymentReference")).isEqualTo("");
        var ledger=get("/lesson-ledger",boss);
        assertThat(ledger.findValues("studentId").stream().map(JsonNode::asLong).toList()).contains(student);
        assertThat(ledger.toString()).doesNotContain("隐私甲孩子");
    }
    @Test void erasureClearsHistoryAndExpiredSafetyTextWithoutBreakingOperationalLists() {
        String privateText="待脱敏的学生个人正文";
        long homework=homework(student);
        db.update("UPDATE homework SET comment=? WHERE id=?",privateText,homework);
        long summary=db.insert("INSERT INTO daily_summary(student_id,date,status,content,teacher_comment,review_comment,review_content,review_teacher_comment) VALUES(?,?,'DRAFT',?,?,?,?,?)",student,sec.today(),privateText,privateText,privateText,privateText,privateText);
        db.update("INSERT INTO summary_review_history(summary_id,action,to_status,version,actor_id,actor_name,actor_role,comment,content,teacher_comment,created_at) VALUES(?,'EDIT','DRAFT',0,?,'测试教师','TEACHER',?,?,?,?)",summary,teacherId,privateText,privateText,privateText,sec.now());
        long mistake=db.insert("INSERT INTO mistake(student_id,subject,title,knowledge_point,error_reason,status,created_at) VALUES(?,'数学',?,?,?,'UNCORRECTED',?)",student,privateText,privateText,privateText,sec.now());
        db.update("INSERT INTO mistake_history(mistake_id,action,comment) VALUES(?,'LEGACY_CREATE',?)",mistake,privateText);
        long leave=post("/leaves",parent,Map.of("studentId",student,"date",sec.today().toString(),"reason",privateText,"requestId",UUID.randomUUID().toString())).path("id").asLong();
        long pickup=post("/pickups",parent,Map.of("studentId",student,"date",sec.today().plusDays(1).toString(),"pickupName",privateText,"relationship","临时监护人","phone","13800000000","requestId",UUID.randomUUID().toString())).path("id").asLong();
        long verification=db.insert("INSERT INTO pickup_verification(authorization_id,authorization_version,student_id,actor_id,verified_at,expires_at,request_id,request_hash) VALUES(?,0,?,?,?,?,?,?)",pickup,student,teacherId,sec.now(),sec.now().plusMinutes(5),UUID.randomUUID().toString(),sec.hash("verification-fixture"));
        long erasure=post("/privacy/requests",parent,Map.of("studentId",student,"type","ERASURE","reason",privateText)).path("id").asLong();
        post("/privacy/requests/"+erasure+"/review",boss,Map.of("decision","APPROVE","note",privateText));
        long actor=sec.authenticate(boss).id();
        long ownAudit=db.insert("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,'核对人',?,'HISTORICAL_CHECK_OUT',?,?)",actor,campus,"student="+student+", date="+sec.today()+", reason="+privateText,sec.now());
        long otherAudit=db.insert("INSERT INTO audit_log(actor_id,actor_name,campus_id,action,detail,created_at) VALUES(?,'核对人',?,'HISTORICAL_CHECK_OUT',?,?)",actor,campus,"student="+second+", date="+sec.today()+", reason=其他学生正文",sec.now());
        assertThat(get("/privacy/requests/"+erasure+"/erasure-check",boss).path("blocks").toString()).contains("请假","接送授权","接送核验");
        db.update("UPDATE leave_request SET leave_date=? WHERE id=?",sec.today().minusDays(1),leave);
        db.update("UPDATE pickup_authorization SET valid_from=?,expires_at=? WHERE id=?",sec.now().minusHours(2),sec.now().minusHours(1),pickup);
        db.update("UPDATE pickup_verification SET expires_at=? WHERE id=?",sec.now().minusMinutes(1),verification);
        assertThat(get("/privacy/requests/"+erasure+"/erasure-check",boss).path("blocks")).isEmpty();
        post("/privacy/requests/"+erasure+"/erase",boss,Map.of("confirmed",true,"note","过期安全事务不阻止脱敏"));
        for(String query:List.of("SELECT * FROM homework WHERE student_id=?","SELECT * FROM daily_summary WHERE student_id=?","SELECT * FROM mistake WHERE student_id=?","SELECT * FROM leave_request WHERE student_id=?","SELECT * FROM pickup_authorization WHERE student_id=?","SELECT * FROM safety_log WHERE student_id=?","SELECT * FROM growth_event WHERE student_id=?","SELECT * FROM privacy_request WHERE student_id=?")) assertThat(db.list(query,student).toString()).doesNotContain(privateText);
        assertThat(db.list("SELECT * FROM summary_review_history WHERE summary_id=?",summary).toString()).doesNotContain(privateText);
        assertThat(db.list("SELECT * FROM mistake_history WHERE mistake_id=?",mistake).toString()).doesNotContain(privateText);
        assertThat(db.one("SELECT detail FROM audit_log WHERE id=?",ownAudit).get("detail")).isEqualTo("student="+student+", date="+sec.today()+", reason=已随资料脱敏");
        assertThat(db.one("SELECT detail FROM audit_log WHERE id=?",otherAudit).get("detail").toString()).contains("其他学生正文");
        for(String endpoint:List.of("/students","/summaries","/mistakes","/attendance","/leaves","/pickups")) {
            var rows=get(endpoint,boss);
            for(var row:rows) assertThat(row.path(endpoint.equals("/students")?"id":"studentId").asLong()).isNotEqualTo(student);
        }
        assertThat(get("/growth",boss).path("student").path("id").asLong()).isEqualTo(second);
        call(HttpMethod.GET,"/students/"+student,parent,null,403);
        call(HttpMethod.GET,"/students/"+student,boss,null,409);
    }
}
