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
    "spring.datasource.url=jdbc:h2:mem:classreports;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/class-report-uploads"})
@ActiveProfiles("local")
class ClassReportIntegrationTest {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Db db;@Autowired Security sec;
    String teacher,colleague,principal,boss,parent,date;long own,other,foreign,teacherId,principalId;
    JsonNode call(HttpMethod method,String path,String token,Long campus,Object body,int status) {
        HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);if(campus!=null)h.set("X-Campus-Id",campus.toString());
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,h),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try{return json.readTree(response.getBody());}catch(Exception e){throw new AssertionError(e);}
    }
    String token(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"fixture-unused-hash","虚构日报老师",role);}
    long classroom(long campus){return db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES(?,?,?)","虚构日报班","虚构老师",campus);}
    @BeforeEach void setup(){
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=call(HttpMethod.POST,"/auth/demo",null,null,Map.of("role","ADMIN"),200).path("token").asText();date=sec.today().toString();
        own=classroom(1);other=classroom(1);foreign=classroom(2);
        teacherId=user("TEACHER");long colleagueId=user("TEACHER");principalId=user("ADMIN");
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,own);db.update("INSERT INTO teacher_class VALUES(?,?)",colleagueId,other);db.update("INSERT INTO user_campus VALUES(?,?)",principalId,1);
        teacher=token(teacherId);colleague=token(colleagueId);principal=token(principalId);parent=token(user("PARENT"));
    }
    String page(long cl){return "/class-reports?classId="+cl+"&date="+date;}
    String save(long cl){return "/class-reports/"+cl+"?date="+date;}
    Map<String,Object> body(String content,int version){return new LinkedHashMap<>(Map.of("content",content,"followUp","分步辅导","nextPlan","复习计算步骤","expectedVersion",version));}

    @Test void teacherOnlyReadsAssignedClassesAndPrincipalsAreCampusScoped(){
        var first=call(HttpMethod.GET,"/class-reports",teacher,null,null,200);assertThat(first.path("classId").asLong()).isEqualTo(own);assertThat(first.path("classes").size()).isEqualTo(1);assertThat(first.path("report").isNull()).isTrue();
        call(HttpMethod.GET,page(other),teacher,null,null,403);call(HttpMethod.PUT,save(other),teacher,null,body("越班记录",0),403);
        call(HttpMethod.GET,page(own),colleague,null,null,403);call(HttpMethod.GET,page(foreign),principal,null,null,403);
        call(HttpMethod.PUT,save(foreign),principal,null,body("越校区记录",0),403);call(HttpMethod.GET,page(own),teacher,2L,null,403);
        call(HttpMethod.GET,page(own),principal,null,null,200);call(HttpMethod.GET,page(foreign),boss,null,null,200);
        call(HttpMethod.GET,page(own),parent,null,null,403);call(HttpMethod.PUT,save(own),parent,null,body("家长尝试",0),403);call(HttpMethod.GET,"/class-reports",null,null,null,401);
    }
    @Test void saveReadEditRetryAndAuditUseServerIdentity(){
        var created=call(HttpMethod.PUT,save(own),teacher,null,body("本班按计划完成辅导",0),200);
        assertThat(created.path("classId").asLong()).isEqualTo(own);assertThat(created.path("createdBy").asLong()).isEqualTo(teacherId);assertThat(created.path("version").asInt()).isEqualTo(1);
        assertThat(call(HttpMethod.PUT,save(own),teacher,null,body("本班按计划完成辅导",0),200).path("version").asInt()).isEqualTo(1);
        var edited=call(HttpMethod.PUT,save(own),teacher,null,body("新增：已完成订正辅导",1),200);assertThat(edited.path("version").asInt()).isEqualTo(2);
        assertThat(call(HttpMethod.GET,page(own),principal,null,null,200).path("report").path("content").asText()).contains("已完成订正");
        assertThat(db.count("SELECT COUNT(*) FROM class_daily_report WHERE class_id=? AND business_date=?",own,date)).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE actor_id=? AND action='CLASS_REPORT_SAVE' AND campus_id=1",teacherId)).isEqualTo(2);
        var spoof=body("尝试修改记录人",2);spoof.put("createdBy",principalId);call(HttpMethod.PUT,save(own),teacher,null,spoof,400);
    }
    @Test void concurrentCreationIsUniqueAndStaleEditsCannotOverwrite(){
        List<CompletableFuture<JsonNode>> pending=new ArrayList<>();for(int i=0;i<4;i++)pending.add(CompletableFuture.supplyAsync(()->call(HttpMethod.PUT,save(own),teacher,null,body("共同重试日报",0),200)));
        for(var result:pending)assertThat(result.join().path("version").asInt()).isEqualTo(1);
        call(HttpMethod.PUT,save(own),principal,null,body("校长补充跟进",1),200);call(HttpMethod.PUT,save(own),teacher,null,body("旧页面尝试覆盖",1),409);
        assertThat(call(HttpMethod.GET,page(own),teacher,null,null,200).path("report").path("content").asText()).isEqualTo("校长补充跟进");
    }
    @Test void invalidInputsFutureDatesAndRevokedGrantsAreRejected(){
        call(HttpMethod.PUT,save(own),teacher,null,body(" ",0),400);call(HttpMethod.PUT,save(own),teacher,null,body("长".repeat(6001),0),400);
        var bad=body("正常内容",0);bad.put("followUp","长".repeat(2001));call(HttpMethod.PUT,save(own),teacher,null,bad,400);
        bad=body("正常内容",0);bad.remove("expectedVersion");call(HttpMethod.PUT,save(own),teacher,null,bad,400);
        call(HttpMethod.PUT,"/class-reports/"+own+"?date="+sec.today().plusDays(1),teacher,null,body("未来事实",0),400);
        db.update("DELETE FROM teacher_class WHERE user_id=?",teacherId);
        call(HttpMethod.GET,page(own),teacher,null,null,403);call(HttpMethod.PUT,save(own),teacher,null,body("撤权后写入",0),403);
        assertThat(call(HttpMethod.GET,"/class-reports",teacher,null,null,200).path("classId").isNull()).isTrue();
    }
    @Test void overviewMatchesClassAttendanceAndHomeworkAndNeverIncludesAnotherClass(){
        long sid=db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('虚构日报学生','男','三年级',?,'虚构家长','13800001234')",own);
        db.update("INSERT INTO attendance(student_id,date,status,check_in_time,source) VALUES(?,?,'PRESENT',?,'DEMO')",sid,date,sec.now());
        var first=call(HttpMethod.GET,page(own),teacher,null,null,200).path("overview");assertThat(first.path("studentCount").asInt()).isEqualTo(1);assertThat(first.path("arrivedCount").asInt()).isEqualTo(1);assertThat(first.path("homeworkCount").asInt()).isZero();
        call(HttpMethod.POST,"/homework/tasks",teacher,null,Map.of("date",date,"subject","语文","type","WRITTEN","title","虚构日报作业","studentIds",List.of(sid)),200);
        var second=call(HttpMethod.GET,page(own),teacher,null,null,200).path("overview");assertThat(second.path("homeworkCount").asInt()).isEqualTo(1);assertThat(second.path("completedHomeworkCount").asInt()).isZero();
        assertThat(call(HttpMethod.GET,page(other),principal,null,null,200).path("overview").path("studentCount").asInt()).isZero();
    }
}
