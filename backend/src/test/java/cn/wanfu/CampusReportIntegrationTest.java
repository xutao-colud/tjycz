package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campusreports;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/campus-report-uploads"})
@ActiveProfiles("local")
class CampusReportIntegrationTest {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Db db;@Autowired Security sec;
    String boss,principal,otherPrincipal,teacher,parent,date;long campus,foreign,ownClass,otherClass,principalId,teacherId;
    JsonNode call(HttpMethod method,String path,String token,Long context,Object body,int status) {
        HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);if(context!=null)h.set("X-Campus-Id",context.toString());
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,h),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try{return json.readTree(response.getBody());}catch(Exception e){throw new AssertionError(e);}
    }
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"fixture-unused-hash","虚构总结验收"+role,role);}
    String token(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    @BeforeEach void setup(){
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=call(HttpMethod.POST,"/auth/demo",null,null,Map.of("role","ADMIN"),200).path("token").asText();date=sec.today().toString();
        campus=db.insert("INSERT INTO campus(name,address) VALUES('虚构总结校区','虚构地址')");foreign=db.insert("INSERT INTO campus(name,address) VALUES('虚构其他校区','虚构地址')");
        ownClass=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构本班','虚构老师',?)",campus);otherClass=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构外校班','其他老师',?)",foreign);
        principalId=user("ADMIN");long other=user("ADMIN");teacherId=user("TEACHER");db.update("INSERT INTO user_campus VALUES(?,?)",principalId,campus);db.update("INSERT INTO user_campus VALUES(?,?)",other,foreign);db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,ownClass);
        principal=token(principalId);otherPrincipal=token(other);teacher=token(teacherId);parent=token(user("PARENT"));
    }
    String page(long id){return "/campus-reports?campusId="+id+"&date="+date;}
    String save(long id){return "/campus-reports/"+id+"?date="+date;}
    String classPage(){return "/class-reports?classId="+ownClass+"&date="+date;}
    String comment(long id){return "/class-reports/"+id+"/comments?date="+date;}
    Map<String,Object> summary(String text,int version){return new LinkedHashMap<>(Map.of("content",text,"followUp","继续跟进班级辅导","nextPlan","安排教研","expectedVersion",version));}
    Map<String,Object> note(String text,String id){return new LinkedHashMap<>(Map.of("text",text,"requestId",id));}

    @Test void campusSummariesAreAdminOnlyAndHonorGrantsAndContext(){
        call(HttpMethod.GET,page(campus),teacher,null,null,403);call(HttpMethod.PUT,save(campus),teacher,null,summary("越权总结",0),403);call(HttpMethod.GET,page(campus),parent,null,null,403);
        call(HttpMethod.GET,page(foreign),principal,null,null,403);call(HttpMethod.PUT,save(foreign),principal,null,summary("越校区总结",0),403);
        call(HttpMethod.GET,page(campus),otherPrincipal,null,null,403);call(HttpMethod.GET,"/campus-reports",null,null,null,401);
        var first=call(HttpMethod.GET,"/campus-reports",principal,null,null,200);assertThat(first.path("campusId").asLong()).isEqualTo(campus);assertThat(first.path("campuses").size()).isEqualTo(1);assertThat(first.path("classes").size()).isEqualTo(1);
        call(HttpMethod.GET,page(foreign),boss,null,null,200);call(HttpMethod.GET,page(foreign),boss,campus,null,403);
        db.update("DELETE FROM user_campus WHERE user_id=?",principalId);call(HttpMethod.GET,page(campus),principal,null,null,403);assertThat(call(HttpMethod.GET,"/campus-reports",principal,null,null,200).path("campusId").isNull()).isTrue();
    }
    @Test void summariesAreDateScopedVersionedIdempotentAndAudited(){
        var first=call(HttpMethod.PUT,save(campus),principal,null,summary("校区教学有序",0),200);assertThat(first.path("createdBy").asLong()).isEqualTo(principalId);assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(call(HttpMethod.PUT,save(campus),principal,null,summary("校区教学有序",0),200).path("id")).isEqualTo(first.path("id"));
        call(HttpMethod.PUT,save(campus),boss,null,summary("补充教研安排",1),200);call(HttpMethod.PUT,save(campus),principal,null,summary("旧页覆盖",1),409);
        assertThat(call(HttpMethod.GET,page(campus),principal,null,null,200).path("summary").path("content").asText()).isEqualTo("补充教研安排");
        assertThat(call(HttpMethod.GET,"/campus-reports?campusId="+campus+"&date="+sec.today().minusDays(1),principal,null,null,200).path("summary").isNull()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM campus_daily_report WHERE campus_id=?",campus)).isEqualTo(1);assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE actor_id=? AND campus_id=? AND action='CAMPUS_REPORT_SAVE'",principalId,campus)).isEqualTo(1);
    }
    @Test void commentsPreserveTeacherReportAndAreReadableOnlyInTheClassScope(){
        var original=call(HttpMethod.PUT,"/class-reports/"+ownClass+"?date="+date,teacher,null,summary("老师原始日报",0),200);
        var created=call(HttpMethod.POST,comment(ownClass),principal,null,note("继续保持分组辅导",UUID.randomUUID().toString()),200);
        assertThat(created.path("actorId").asLong()).isEqualTo(principalId);assertThat(created.path("actorRole").asText()).isEqualTo("ADMIN");assertThat(created.has("requestKey")).isFalse();
        var visible=call(HttpMethod.GET,classPage(),teacher,null,null,200);assertThat(visible.path("report").path("content").asText()).isEqualTo("老师原始日报");assertThat(visible.path("report").path("version")).isEqualTo(original.path("version"));assertThat(visible.path("comments").get(0).path("text").asText()).contains("分组辅导");
        var card=call(HttpMethod.GET,page(campus),principal,null,null,200).path("classes").get(0);assertThat(card.path("report").path("content").asText()).isEqualTo("老师原始日报");assertThat(card.path("comments").size()).isEqualTo(1);
        call(HttpMethod.POST,comment(ownClass),teacher,null,note("教师伪装点评",UUID.randomUUID().toString()),403);call(HttpMethod.POST,comment(ownClass),parent,null,note("家长点评",UUID.randomUUID().toString()),403);call(HttpMethod.POST,comment(otherClass),principal,null,note("跨校点评",UUID.randomUUID().toString()),403);call(HttpMethod.GET,classPage(),otherPrincipal,null,null,403);
        call(HttpMethod.GET,classPage(),parent,null,null,403);
    }
    @Test void concurrentCommentRetriesCreateOneRecordAndRejectReusedKeys(){
        String id=UUID.randomUUID().toString();List<CompletableFuture<JsonNode>> pending=new ArrayList<>();for(int i=0;i<4;i++)pending.add(CompletableFuture.supplyAsync(()->call(HttpMethod.POST,comment(ownClass),principal,null,note("同一条校长点评",id),200)));
        var first=pending.get(0).join();for(var response:pending)assertThat(response.join().path("id")).isEqualTo(first.path("id"));assertThat(db.count("SELECT COUNT(*) FROM class_report_comment WHERE class_id=?",ownClass)).isEqualTo(1);
        call(HttpMethod.POST,comment(ownClass),principal,null,note("更换内容复用标识",id),409);assertThat(call(HttpMethod.GET,classPage(),teacher,null,null,200).path("comments").size()).isEqualTo(1);
        var spoof=note("伪造点评人",UUID.randomUUID().toString());spoof.put("actorId",teacherId);call(HttpMethod.POST,comment(ownClass),principal,null,spoof,400);
        db.update("DELETE FROM teacher_class WHERE user_id=?",teacherId);call(HttpMethod.GET,classPage(),teacher,null,null,403);
    }
    @Test void overviewAndValidationUseTheSelectedCampusAndBusinessDate(){
        long student=db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('虚构总结学生','男','三年级',?,'虚构家长','13800001234')",ownClass);
        db.update("INSERT INTO attendance(student_id,date,status,check_in_time,source) VALUES(?,?,'PRESENT',?,'DEMO')",student,date,sec.now());db.update("INSERT INTO teacher_attendance(user_id,campus_id,date,check_in_time) VALUES(?,?,?,?)",teacherId,campus,date,sec.now());
        var stats=call(HttpMethod.GET,page(campus),principal,null,null,200).path("overview");assertThat(stats.path("studentCount").asInt()).isEqualTo(1);assertThat(stats.path("arrivedCount").asInt()).isEqualTo(1);assertThat(stats.path("classCount").asInt()).isEqualTo(1);assertThat(stats.path("checkedInTeacherCount").asInt()).isEqualTo(1);
        call(HttpMethod.PUT,save(campus),principal,null,summary(" ",0),400);call(HttpMethod.PUT,save(campus),principal,null,summary("长".repeat(6001),0),400);
        var missing=summary("正常",0);missing.remove("expectedVersion");call(HttpMethod.PUT,save(campus),principal,null,missing,400);
        call(HttpMethod.POST,comment(ownClass),principal,null,note("长".repeat(2001),UUID.randomUUID().toString()),400);
        call(HttpMethod.PUT,"/campus-reports/"+campus+"?date="+sec.today().plusDays(1),principal,null,summary("未来总结",0),400);call(HttpMethod.POST,"/class-reports/"+ownClass+"/comments?date="+sec.today().plusDays(1),principal,null,note("未来点评",UUID.randomUUID().toString()),400);
    }
}
