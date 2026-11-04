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
    "spring.datasource.url=jdbc:h2:mem:campus;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/campus-uploads"})
@ActiveProfiles("local")
class CampusIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired Db db;
    @Autowired Security security;
    @BeforeEach void configureHttp() { http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()); }
    JsonNode request(HttpMethod method,String path,String token,Long campus,Object body,int status) {
        if(method==HttpMethod.POST && status==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,security,body);
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        if(campus!=null) headers.set("X-Campus-Id",campus.toString());
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try { return response.getBody()==null?mapper.nullNode():mapper.readTree(response.getBody()); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode get(String path,String token,Long campus) { return request(HttpMethod.GET,path,token,campus,null,200); }
    String demo(String role) { return request(HttpMethod.POST,"/auth/demo",null,null,Map.of("role",role),200).path("token").asText(); }
    long fixtureUser(String role) {
        return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"test-unused-hash","考勤测试"+role,role);
    }
    String fixtureToken(long id) { return security.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString(); }
    JsonNode loginFixture(long id,String password) {
        String username=db.one("SELECT username FROM app_user WHERE id=?",id).get("username").toString();
        Map<String,Object> challenge=mapper.convertValue(get("/auth/challenge",null,null),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        return request(HttpMethod.POST,"/auth/login",null,null,PasswordTransportTestClient.encrypt(challenge,"LOGIN",Map.of("username",username,"password",password)),200);
    }
    List<Long> grantIds(JsonNode identity,String field) {
        var ids=identity.path(field); assertThat(ids.isArray()).as("identity."+field).isTrue();
        List<Long> values=new ArrayList<>(); ids.forEach(value->values.add(value.asLong())); return values;
    }
    void assertOwnGrants(JsonNode identity,long id,long campus,List<Long> classes,List<Long> students) {
        assertThat(identity.path("id").asLong()).isEqualTo(id);
        assertThat(grantIds(identity,"campusIds")).containsExactly(campus);
        assertThat(grantIds(identity,"classIds")).containsExactlyElementsOf(classes);
        assertThat(grantIds(identity,"studentIds")).containsExactlyElementsOf(students);
    }
    @Test void sameCampusBindingChangesRefreshOwnIdentityAndExistingSessions() {
        String boss=demo("ADMIN"),password="BindingFixtureOnly2026!";
        long campus=request(HttpMethod.POST,"/campuses",boss,null,Map.of("name","绑定更新"+UUID.randomUUID()),200).path("id").asLong();
        long ca=request(HttpMethod.POST,"/classes",boss,campus,Map.of("name","绑定A班","teacherName","测试老师"),200).path("id").asLong();
        long cb=request(HttpMethod.POST,"/classes",boss,campus,Map.of("name","绑定B班","teacherName","测试老师"),200).path("id").asLong();
        long sa=request(HttpMethod.POST,"/students",boss,campus,Map.of("name","绑定学生A","gender","男","grade","三年级","classId",ca,"parentName","测试家长","parentPhone","13800000000"),200).path("id").asLong();
        long sb=request(HttpMethod.POST,"/students",boss,campus,Map.of("name","绑定学生B","gender","女","grade","三年级","classId",cb,"parentName","测试家长","parentPhone","13800000000"),200).path("id").asLong();
        long teacher=fixtureUser("TEACHER"),parent=fixtureUser("PARENT");
        db.update("UPDATE app_user SET password_hash=? WHERE id IN (?,?)",security.passwords.encode(password),teacher,parent);
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,ca);
        db.update("INSERT INTO parent_student VALUES(?,?)",parent,sa);
        var teacherLogin=loginFixture(teacher,password); var parentLogin=loginFixture(parent,password);
        String teacherToken=teacherLogin.path("token").asText(),parentToken=parentLogin.path("token").asText();
        assertOwnGrants(teacherLogin.path("user"),teacher,campus,List.of(ca),List.of());
        assertOwnGrants(parentLogin.path("user"),parent,campus,List.of(),List.of(sa));
        assertOwnGrants(get("/me",teacherToken,campus),teacher,campus,List.of(ca),List.of());
        assertOwnGrants(get("/me",parentToken,campus),parent,campus,List.of(),List.of(sa));
        request(HttpMethod.PUT,"/users/"+teacher+"/access",boss,null,Map.of("classIds",List.of(cb)),200);
        request(HttpMethod.PUT,"/users/"+parent+"/access",boss,null,Map.of("studentIds",List.of(sb)),200);
        assertOwnGrants(get("/me",teacherToken,campus),teacher,campus,List.of(cb),List.of());
        assertOwnGrants(get("/me",parentToken,campus),parent,campus,List.of(),List.of(sb));
        assertOwnGrants(loginFixture(teacher,password).path("user"),teacher,campus,List.of(cb),List.of());
        assertOwnGrants(loginFixture(parent,password).path("user"),parent,campus,List.of(),List.of(sb));
        for(String token:List.of(teacherToken,parentToken)) {
            request(HttpMethod.GET,"/students/"+sa,token,campus,null,403);
            var visible=get("/students",token,campus);
            assertThat(visible.size()).isEqualTo(1);
            assertThat(visible.get(0).path("id").asLong()).isEqualTo(sb);
        }
    }
    @Test void demoIdentityAndCampusHeaderNarrowEveryList() {
        String superAdmin=demo("ADMIN"),principal=demo("CAMPUS_ADMIN");
        var me=get("/me",superAdmin,null);
        assertThat(me.path("role").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(me.path("isSuperAdmin").asBoolean()).isTrue();
        assertThat(get("/campuses",superAdmin,null).size()).isGreaterThanOrEqualTo(2);
        assertThat(get("/me",principal,null).path("role").asText()).isEqualTo("ADMIN");
        assertThat(get("/campuses",principal,null).size()).isEqualTo(1);
        assertThat(get("/students",principal,null).size()).isEqualTo(8);
        request(HttpMethod.GET,"/students",principal,2L,null,403);
        assertThat(get("/students",superAdmin,2L).size()).isPositive();
        assertThat(get("/me",superAdmin,1L).path("campusIds").size()).isGreaterThanOrEqualTo(2);
    }
    @Test void principalOverviewCountsMatchScopedLists() {
        String principal=demo("CAMPUS_ADMIN");
        String day="2000-02-01";
        var campus=get("/campuses",principal,null).get(0);
        long campusId=campus.path("id").asLong();
        var students=get("/students",principal,null);
        long first=students.get(0).path("id").asLong(), second=students.get(1).path("id").asLong();
        db.update("INSERT INTO attendance(student_id,date,status,source) VALUES(?,?,'PRESENT','MANUAL')",first,day);
        db.update("INSERT INTO attendance(student_id,date,status,source) VALUES(?,?,'LEFT','MANUAL')",second,day);
        for(String name:List.of("测试预警课程甲","测试预警课程乙")) {
            long course=db.insert("INSERT INTO course(campus_id,name,type,unit,price,warning_threshold,enabled) VALUES(?,?,'EVENING','次',0,1,TRUE)",campusId,name);
            db.update("INSERT INTO lesson_account(student_id,course_id,balance) VALUES(?,?,0)",first,course);
        }
        String path="/principal-overview?date="+day;
        var overview=get(path,principal,campusId);
        assertThat(overview.path("expectedCount").asInt()).isEqualTo(students.size());
        assertThat(overview.path("arrived").size()).isEqualTo(2);
        assertThat(overview.path("present").size()).isEqualTo(1);
        assertThat(overview.path("present").get(0).path("id").asLong()).isEqualTo(first);
        long duplicates=0;
        for(var row:overview.path("warnings")) {
            if(row.path("id").asLong()==first) duplicates++;
            assertThat(row.has("parentPhone")).isFalse();
        }
        assertThat(duplicates).isEqualTo(1);
        request(HttpMethod.GET,path,principal,2L,null,403);
        request(HttpMethod.GET,path,demo("TEACHER"),null,null,403);
        request(HttpMethod.GET,path,demo("PARENT"),null,null,403);
        var today=get("/principal-overview",principal,campusId);
        assertThat(today.path("todos").isArray()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='SENSITIVE_READ' AND detail LIKE '%principal-overview%'")).isPositive();
    }

    @Test void teacherAttendanceIsScopedSelfOnlyIdempotentAndUsesCurrentGrants() {
        String boss=demo("ADMIN"),parent=demo("PARENT");
        long a=request(HttpMethod.POST,"/campuses",boss,null,Map.of("name","考勤A"),200).path("id").asLong();
        long b=request(HttpMethod.POST,"/campuses",boss,null,Map.of("name","考勤B"),200).path("id").asLong();
        long ca=request(HttpMethod.POST,"/classes",boss,a,Map.of("name","考勤A班","teacherName","测试老师"),200).path("id").asLong();
        long cb=request(HttpMethod.POST,"/classes",boss,b,Map.of("name","考勤B班","teacherName","测试老师"),200).path("id").asLong();
        long teacher=fixtureUser("TEACHER"),other=fixtureUser("TEACHER"),principal=fixtureUser("ADMIN");
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,ca);
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,cb);
        db.update("INSERT INTO teacher_class VALUES(?,?)",other,cb);
        db.update("INSERT INTO user_campus VALUES(?,?)",principal,a);
        String token=fixtureToken(teacher),principalToken=fixtureToken(principal);
        assertThat(get("/teacher-attendance",token,null).size()).isEqualTo(2);
        assertThat(get("/teacher-attendance",principalToken,null).size()).isEqualTo(1);
        assertThat(get("/teacher-attendance",boss,b).size()).isEqualTo(2);
        request(HttpMethod.GET,"/teacher-attendance",principalToken,b,null,403);
        request(HttpMethod.GET,"/teacher-attendance",parent,null,null,403);
        request(HttpMethod.GET,"/teacher-attendance",null,null,null,401);
        request(HttpMethod.POST,"/teacher-attendance/check-in",boss,a,Map.of("campusId",a),403);
        request(HttpMethod.POST,"/teacher-attendance/check-in",principalToken,a,Map.of("campusId",a),403);
        request(HttpMethod.POST,"/teacher-attendance/check-in",parent,null,Map.of("campusId",a),403);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,null,Map.of("campusId",a,"userId",other),400);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,null,Map.of("campusId",a,"date","2000-01-01"),400);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,null,Map.of(),400);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,null,Map.of("campusId",1),403);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,a,Map.of("campusId",b),403);
        List<CompletableFuture<JsonNode>> attempts=new ArrayList<>();
        for(int i=0;i<4;i++) attempts.add(CompletableFuture.supplyAsync(()->request(HttpMethod.POST,"/teacher-attendance/check-in",token,a,Map.of("campusId",a),200)));
        var first=attempts.get(0).join();
        for(var attempt:attempts) {
            assertThat(attempt.join().path("id")).isEqualTo(first.path("id"));
            assertThat(attempt.join().path("checkInTime")).isEqualTo(first.path("checkInTime"));
        }
        assertThat(first.path("date").asText()).isEqualTo(security.today().toString());
        assertThat(db.count("SELECT COUNT(*) FROM teacher_attendance WHERE user_id=? AND campus_id=?",teacher,a)).isEqualTo(1);
        var dashboard=get("/dashboard",principalToken,null);
        assertThat(dashboard.path("teacherCount").asInt()).isEqualTo(1);
        assertThat(dashboard.path("checkedInTeacherCount").asInt()).isEqualTo(1);
        assertThat(get("/dashboard",boss,b).path("checkedInTeacherCount").asInt()).isZero();
        assertThat(get("/teacher-attendance?date=2000-01-01",principalToken,null).get(0).path("status").asText()).isEqualTo("NOT_CHECKED_IN");
        request(HttpMethod.PUT,"/users/"+principal+"/access",principalToken,null,Map.of("campusIds",List.of(a,b)),403);
        request(HttpMethod.PUT,"/users/"+principal+"/access",boss,null,Map.of("campusIds",List.of(a,b)),200);
        assertThat(get("/teacher-attendance",principalToken,b).size()).isEqualTo(2);
        request(HttpMethod.PUT,"/users/"+teacher+"/access",boss,null,Map.of("classIds",List.of(cb)),200);
        request(HttpMethod.GET,"/teacher-attendance",token,a,null,403);
        request(HttpMethod.POST,"/teacher-attendance/check-in",token,null,Map.of("campusId",a),403);
        assertThat(get("/teacher-attendance",token,null).size()).isEqualTo(1);
        request(HttpMethod.PUT,"/users/"+principal+"/access",boss,null,Map.of("campusIds",List.of()),200);
        assertThat(get("/teacher-attendance",principalToken,null)).isEmpty();
    }
}
