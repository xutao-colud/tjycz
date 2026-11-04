package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.profiles.active=local","app.privacy.consent-required=false","spring.datasource.url=jdbc:h2:mem:consent_disabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","app.upload-dir=./target/consent-disabled-uploads"})
class GuardianConsentDisabledIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    @MockBean QuestionOcrClient ocr;
    String parent,otherParent,teacher,otherTeacher;long parentId,teacherId,student,otherStudent,foreignStudent,campus;byte[] png;
    String key(){return UUID.randomUUID().toString();}
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",key(),"unused-fixture-hash","虚构免授权验收"+role,role);}
    String token(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    long child(long cl){return db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('虚构学生','男','三年级',?,'虚构家长','13800000000')",cl);}
    JsonNode call(HttpMethod method,String route,String token,Object body,int code){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
        var response=http.exchange("/api"+route,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+route+" "+response.getBody()).isEqualTo(code);
        try{return json.readTree(response.getBody());}catch(Exception error){throw new AssertionError(error);}
    }
    JsonNode get(String path,String token,int code){return call(HttpMethod.GET,path,token,null,code);}
    long photo(long owner)throws Exception{
        var dir=Path.of("target/consent-disabled-uploads");Files.createDirectories(dir);String name=key();Files.write(dir.resolve(name),png);
        return db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,'fixture.png',?,'image/png',?,?,?)",owner,name,png.length,sec.now(),campus);
    }
    @BeforeEach void setup()throws Exception{
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        var page=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();ImageIO.write(page,"png",output);png=output.toByteArray();
        campus=db.insert("INSERT INTO campus(name) VALUES('虚构免授权校区')");long foreignCampus=db.insert("INSERT INTO campus(name) VALUES('虚构其他校区')");
        long cl=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构本班','老师',?)",campus),otherCl=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构其他班','老师',?)",campus),foreignCl=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构外校班','老师',?)",foreignCampus);
        student=child(cl);otherStudent=child(otherCl);foreignStudent=child(foreignCl);parentId=user("PARENT");teacherId=user("TEACHER");long otherParentId=user("PARENT"),otherTeacherId=user("TEACHER");
        db.update("INSERT INTO parent_student VALUES(?,?)",parentId,student);db.update("INSERT INTO parent_student VALUES(?,?)",otherParentId,otherStudent);db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,cl);db.update("INSERT INTO teacher_class VALUES(?,?)",otherTeacherId,otherCl);
        parent=token(parentId);otherParent=token(otherParentId);teacher=token(teacherId);otherTeacher=token(otherTeacherId);
        when(ocr.configured()).thenReturn(true);when(ocr.recognize(any(byte[].class))).thenReturn(Map.of("engine","fixture-fast-locator","pageImage",Base64.getEncoder().encodeToString(png),"questions",List.of(Map.of("number","1","group","题目","text","","regions",List.of(Map.of("x",.05,"y",.05,"width",.9,"height",.9)),"warnings",List.of()))));
    }
    @Test void disabledGateAllowsAvatarWithoutFabricatingConsentAndKeepsDataScopes()throws Exception{
        for(String scope:List.of("SERVICE","MEDIA"))db.update("INSERT INTO privacy_consent(student_id,user_id,scope,policy_version,granted,created_at) VALUES(?,?,?,?,FALSE,?)",student,parentId,scope,PrivacyService.POLICY_VERSION,sec.now());
        assertThat(get("/privacy/policy",parent,200).path("consentRequired").asBoolean()).isFalse();var state=get("/privacy/consents?studentId="+student,parent,200);
        assertThat(state.path("consentRequired").asBoolean()).isFalse();assertThat(state.path("canConsent").asBoolean()).isFalse();assertThat(state.path("effectiveMedia").asBoolean()).isTrue();assertThat(state.path("effectiveService").asBoolean()).isTrue();assertThat(state.path("media").path("status").asText()).isEqualTo("WITHDRAWN");
        long file=photo(parentId);call(HttpMethod.PUT,"/students/"+student+"/avatar",parent,Map.of("avatarFileId",file),200);assertThat(get("/students/"+student,parent,200).path("avatar").path("id").asLong()).isEqualTo(file);
        get("/files/"+file+"/access",teacher,200);get("/files/"+file+"/access",otherParent,403);get("/files/"+file+"/access",otherTeacher,403);get("/students/"+foreignStudent,teacher,403);get("/privacy/consents?studentId="+otherStudent,parent,403);
        call(HttpMethod.POST,"/privacy/consents",parent,Map.of("studentId",student,"scope","MEDIA","policyVersion",PrivacyService.POLICY_VERSION,"granted",true),410);
        call(HttpMethod.POST,"/privacy/consents",otherParent,Map.of("studentId",student,"scope","MEDIA","policyVersion",PrivacyService.POLICY_VERSION,"granted",true),403);assertThat(db.count("SELECT COUNT(*) FROM privacy_consent WHERE student_id=?",student)).isEqualTo(2);
    }
    @Test void scanningNeedsNoConsentButBlockedFilesAndErasedChildrenRemainProtected()throws Exception{
        long file=photo(teacherId);var body=Map.of("studentId",student,"photoFileId",file,"subject","数学","requestId",key());
        var foreign=new HashMap<>(body);foreign.put("studentId",foreignStudent);call(HttpMethod.POST,"/teaching-capture/jobs",teacher,foreign,403);
        long id=call(HttpMethod.POST,"/teaching-capture/jobs",teacher,body,202).path("id").asLong();JsonNode job=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do{job=get("/teaching-capture/jobs/"+id,teacher,200);if(job.path("status").asText().equals("READY"))break;Thread.sleep(60);}while(System.nanoTime()<deadline);
        assertThat(job.path("status").asText()).isEqualTo("READY");assertThat(job.path("questions").size()).isEqualTo(1);assertThat(db.count("SELECT COUNT(*) FROM privacy_consent WHERE student_id=?",student)).isZero();
        long page=job.path("pagePhoto").path("id").asLong();get("/files/"+page+"/access",parent,200);db.update("UPDATE stored_file SET privacy_blocked=TRUE WHERE id=?",page);get("/files/"+page+"/access",parent,403);
        db.update("UPDATE student SET privacy_erased_at=? WHERE id=?",sec.now(),student);get("/files/"+file+"/access",parent,403);
    }
    @Test void dataRequestsStillUseBoundFamilyAndManagerReview(){
        var created=call(HttpMethod.POST,"/privacy/requests",parent,Map.of("studentId",student,"type","EXPORT","reason","虚构副本申请"),200);
        assertThat(get("/privacy/requests",parent,200).toString()).contains(created.path("id").asText());get("/privacy/requests/"+created.path("id").asLong(),otherParent,403);get("/privacy/requests",teacher,403);
    }
}
