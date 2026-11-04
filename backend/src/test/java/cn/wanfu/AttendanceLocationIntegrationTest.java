package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:location;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/location-uploads"})
@ActiveProfiles("local")
class AttendanceLocationIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String boss,teacher,parent,principal; long campus,student;
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(token!=null) headers.setBearerAuth(token);
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try {return json.readTree(response.getBody());} catch(Exception e) {throw new AssertionError(e);}
    }
    String token(long id) { return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString(); }
    long user(String role) { return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"fixture-unused-hash","虚构定位验收",role); }
    @BeforeEach void setup() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=call(HttpMethod.POST,"/auth/demo",null,Map.of("role","ADMIN"),200).path("token").asText();
        campus=call(HttpMethod.POST,"/campuses",boss,Map.of("name","虚构定位校区"),200).path("id").asLong();
        long cl=call(HttpMethod.POST,"/classes",boss,Map.of("campusId",campus,"name","定位验收班","teacherName","虚构老师"),200).path("id").asLong();
        student=call(HttpMethod.POST,"/students",boss,Map.of("classId",cl,"name","虚构定位学生","grade","三年级","gender","男","parentName","虚构家长","parentPhone","13800001234"),200).path("id").asLong();
        long tid=user("TEACHER"),pid=user("PARENT"),aid=user("ADMIN");
        db.update("INSERT INTO teacher_class VALUES(?,?)",tid,cl); db.update("INSERT INTO parent_student VALUES(?,?)",pid,student); db.update("INSERT INTO user_campus VALUES(?,?)",aid,campus);
        teacher=token(tid); parent=token(pid); principal=token(aid);
    }
    Map<String,Object> config(double latitude) {return Map.of("address","虚构签到地点","latitude",latitude,"longitude",121.47,"radiusMeters",200);}
    void configure() {call(HttpMethod.PUT,"/campuses/"+campus+"/check-in-location",principal,config(31.23),200);}
    Map<String,Object> point(double latitude) {return new LinkedHashMap<>(Map.of("latitude",latitude,"longitude",121.47,"accuracy",5,"capturedAt",sec.clock.millis()));}
    Map<String,Object> body(Map<String,Object> point) {return new LinkedHashMap<>(Map.of("campusId",campus,"location",point,"studentIds",List.of(student),"date",sec.today().toString()));}
    JsonNode arrive(Map<String,Object> body) {return call(HttpMethod.POST,"/attendance/check-in",teacher,body,200).path("results").get(0);}

    @Test void configurationRequiresAdminAndCampusGrantAndValidNumbers() {
        String path="/campuses/"+campus+"/check-in-location";
        call(HttpMethod.PUT,path,teacher,config(31.23),403);call(HttpMethod.PUT,path,parent,config(31.23),403);
        call(HttpMethod.PUT,"/campuses/1/check-in-location",principal,config(31.23),403);
        call(HttpMethod.PUT,path,boss,config(91),400);
        var bad=new LinkedHashMap<>(config(31.23)); bad.put("radiusMeters",49);call(HttpMethod.PUT,path,boss,bad,400);
        bad.put("radiusMeters",200.5);call(HttpMethod.PUT,path,boss,bad,400);
        bad.put("radiusMeters",200);bad.put("latitude","31.23");call(HttpMethod.PUT,path,boss,bad,400);
        configure();
        var list=call(HttpMethod.GET,"/campuses",teacher,null,200);
        assertThat(list.get(0).path("checkInRadius").asInt()).isEqualTo(200);
        assertThat(list.get(0).path("checkInAddress").asText()).isEqualTo("虚构签到地点");
    }
    @Test void studentArrivalDoesNotRequireLocationOrCampusSetup() {
        var plain=Map.<String,Object>of("studentIds",List.of(student),"date",sec.today().toString());
        assertThat(arrive(plain).path("success").asBoolean()).isTrue();
        assertThat(db.one("SELECT source FROM attendance WHERE student_id=?",student).get("source")).isEqualTo("MANUAL");
        call(HttpMethod.POST,"/attendance/check-in",parent,plain,403);
        call(HttpMethod.POST,"/attendance/check-in",teacher,Map.of("studentIds",List.of(1),"date",sec.today().toString()),403);
    }
    @Test void studentArrivalIgnoresLegacyPositionAndRetriesWithoutDuplicates() {
        configure();
        var legacy=body(point(32));legacy.put("campusId",1);
        assertThat(arrive(legacy).path("success").asBoolean()).isTrue();
        assertThat(arrive(Map.of("studentIds",List.of(student),"date",sec.today().toString())).path("success").asBoolean()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=?",student)).isEqualTo(1);
    }
    @Test void teachersUseSameRangeAndCurrentServerConfiguration() {
        String path="/teacher-attendance/check-in";
        call(HttpMethod.POST,path,teacher,Map.of("campusId",campus),400);configure();
        call(HttpMethod.POST,path,teacher,Map.of("campusId",campus,"location",point(32)),400);
        var first=call(HttpMethod.POST,path,teacher,Map.of("campusId",campus,"location",point(31.23)),200);
        assertThat(call(HttpMethod.POST,path,teacher,Map.of("campusId",campus,"location",point(31.23)),200).path("id")).isEqualTo(first.path("id"));
        call(HttpMethod.PUT,"/campuses/"+campus+"/check-in-location",principal,config(32),200);
        call(HttpMethod.POST,path,teacher,Map.of("campusId",campus,"location",point(31.23)),400);
    }
    @Test void distanceHandlesIdenticalPointsAndDateline() {
        assertThat(AttendanceLocationService.distance(0,0,0,0)).isZero();
        assertThat(AttendanceLocationService.distance(0,179.999,0,-179.999)).isBetween(222.0,223.0);
    }
}
