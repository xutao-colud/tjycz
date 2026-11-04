package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true","spring.profiles.active=local","spring.datasource.url=jdbc:h2:mem:teaching;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","app.upload-dir=./target/teaching-uploads"})
class TeachingIntegrationTest {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Db db;@Autowired Security sec;
    String teacher,parent,principal,boss,stranger;long child,otherChild,campus,otherCampus,teacherId,parentId,course;
    String key(){return UUID.randomUUID().toString();}
    JsonNode call(HttpMethod method,String path,String token,Object body,int expected){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(expected);try{return json.readTree(response.getBody());}catch(Exception e){throw new AssertionError(e);}
    }
    JsonNode get(String path,String token){return call(HttpMethod.GET,path,token,null,200);}
    JsonNode post(String path,String token,Object body){return call(HttpMethod.POST,path,token,body,200);}
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",key(),"unused-fixture-hash","虚构教学验收"+role,role);}
    String session(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    long student(long classroom){return db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone,points) VALUES('虚构学生','男','三年级',?,'虚构家长','13800000000',10)",classroom);}
    long image(long owner,long campus)throws Exception{String storage=key();Path root=Path.of("target/teaching-uploads");Files.createDirectories(root);BufferedImage image=new BufferedImage(600,180,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,600,180);graphics.setColor(java.awt.Color.BLACK);graphics.drawString("FICTIONAL QUESTION: 12 + 8 = ?",30,80);graphics.dispose();ImageIO.write(image,"png",root.resolve(storage).toFile());return db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,'fictional-question.png',?,'image/png',?,?,?)",owner,storage,Files.size(root.resolve(storage)),sec.now(),campus);}
    @BeforeEach void setup(){
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText();
        campus=db.insert("INSERT INTO campus(name) VALUES('虚构教学校区')");otherCampus=db.insert("INSERT INTO campus(name) VALUES('虚构外校区')");long classroom=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构教学班','虚构教师',?)",campus),otherClass=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构其他班','其他教师',?)",campus);
        child=student(classroom);otherChild=student(otherClass);teacherId=user("TEACHER");parentId=user("PARENT");long principalId=user("ADMIN"),strangerId=user("PARENT");
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,classroom);db.update("INSERT INTO parent_student VALUES(?,?)",parentId,child);db.update("INSERT INTO parent_student VALUES(?,?)",strangerId,otherChild);db.update("INSERT INTO user_campus VALUES(?,?)",principalId,campus);
        teacher=session(teacherId);parent=session(parentId);principal=session(principalId);stranger=session(strangerId);
        for(String scope:List.of("SERVICE","MEDIA"))post("/privacy/consents",parent,Map.of("studentId",child,"scope",scope,"granted",true,"policyVersion",PrivacyService.POLICY_VERSION));
        course=db.insert("INSERT INTO course(name,type,unit,price,warning_threshold,enabled,campus_id) VALUES('虚构周末课','WEEKEND','次',1000,3,TRUE,?)",campus);
    }
    @Test void photoArrivalNotifiesOnlyBoundParentsAndCheckoutStillRequiresHandover()throws Exception{
        long photo=image(teacherId,campus);var in=Map.of("studentId",child,"photoFileId",photo,"action","CHECK_IN");post("/attendance/photo",teacher,in);post("/attendance/photo",teacher,in);
        assertThat(get("/student-notifications?studentId="+child,parent).size()).isEqualTo(1);assertThat(get("/student-notifications?studentId="+child,parent).get(0).path("attachments").size()).isEqualTo(1);
        call(HttpMethod.GET,"/student-notifications?studentId="+child,stranger,null,403);call(HttpMethod.POST,"/attendance/photo",parent,in,403);
        call(HttpMethod.POST,"/attendance/photo",teacher,Map.of("studentId",otherChild,"photoFileId",photo,"action","CHECK_IN"),403);
        var out=Map.of("studentId",child,"photoFileId",photo,"action","CHECK_OUT");call(HttpMethod.POST,"/attendance/photo",teacher,out,400);assertThat(get("/student-notifications?studentId="+child,parent).size()).isEqualTo(1);
        var pickup=post("/pickups",parent,Map.of("studentId",child,"date",sec.today().toString(),"pickupName","虚构接送人","relationship","亲属","phone","13800001234","requestId",key()));
        post("/pickups/"+pickup.path("id").asLong()+"/verify",teacher,Map.of("expectedVersion",pickup.path("version").asInt(),"pickupName","虚构接送人","phoneLast4","1234","code",pickup.path("pickupCode").asText(),"confirmedIdentity",true,"requestId",key()));
        post("/attendance/photo",teacher,out);post("/attendance/photo",teacher,out);var messages=get("/student-notifications?studentId="+child,parent);assertThat(messages.size()).isEqualTo(2);assertThat(messages.get(0).path("deliveryStatus").asText()).isEqualTo("NOT_CONFIGURED");
        long message=messages.get(0).path("id").asLong();post("/student-notifications/"+message+"/read",parent,Map.of());call(HttpMethod.POST,"/student-notifications/"+message+"/read",stranger,Map.of(),403);
        assertThat(db.one("SELECT status FROM attendance WHERE student_id=?",child).get("status")).isEqualTo("LEFT");
    }
    @Test void teacherPickupUsesSeparateCameraEvidenceAndKeepsAbnormalHold() throws Exception {
        post("/lesson-grants",teacher,Map.of("studentId",child,"courseId",course,"units",5,"reason","虚构拍照签退验收","requestId",key()));
        long classroom=Db.id(db.one("SELECT class_id FROM student WHERE id=?",child),"classId");
        post("/schedules",principal,Map.of("classId",classroom,"courseId",course,"date",sec.today().toString(),"startTime","16:00","endTime","18:00","units",1));
        long inPhoto=image(teacherId,campus),outPhoto=image(teacherId,campus);
        var in=Map.of("studentId",child,"photoFileId",inPhoto,"action","CHECK_IN","teacherPickup",true);
        var out=Map.of("studentId",child,"photoFileId",outPhoto,"action","CHECK_OUT","teacherPickup",true);
        call(HttpMethod.POST,"/attendance/photo",teacher,out,400);
        post("/attendance/photo",teacher,in);
        call(HttpMethod.POST,"/attendance/photo",teacher,Map.of("studentId",child,"photoFileId",inPhoto,"action","CHECK_OUT","teacherPickup",true),400);
        var alert=post("/pickup-alerts",teacher,Map.of("studentId",child,"pickupName","虚构接送情况","reason","需核对","requestId",key()));
        call(HttpMethod.POST,"/attendance/photo",teacher,out,400);
        post("/pickup-alerts/"+alert.path("id").asLong()+"/handle",teacher,Map.of("action","RESOLVED","note","已联系家长核对","expectedVersion",alert.path("version").asInt()));
        assertThat(post("/attendance/photo",teacher,out).path("photos").get(0).path("id").asLong()).isEqualTo(outPhoto);
        post("/attendance/photo",teacher,out);
        assertThat(get("/accounts?studentId="+child,parent).get(0).path("balance").decimalValue()).isEqualByComparingTo("4");
        assertThat(db.count("SELECT COUNT(*) FROM pickup_authorization WHERE student_id=?",child)).isZero();
        assertThat(get("/student-notifications?studentId="+child,parent).size()).isEqualTo(2);
        var record=get("/attendance?studentId="+child,parent).get(0);
        assertThat(record.path("status").asText()).isEqualTo("LEFT");
        assertThat(record.path("checkInPhotos").get(0).path("id").asLong()).isEqualTo(inPhoto);
        assertThat(record.path("checkOutPhotos").get(0).path("id").asLong()).isEqualTo(outPhoto);
        call(HttpMethod.GET,"/attendance?studentId="+child,stranger,null,403);
        call(HttpMethod.POST,"/attendance/photo",parent,in,403);
    }
    @Test void grantsCreateIndependentCourseAccountAndDoNotDuplicateOrImpersonateRenewal(){
        assertThat(get("/courses",teacher).findValues("id")).extracting(JsonNode::asLong).contains(course);
        var body=Map.of("studentId",child,"courseId",course,"units",3,"reason","虚构周末课补充","requestId",key());var first=post("/lesson-grants",teacher,body);assertThat(post("/lesson-grants",teacher,body).path("id")).isEqualTo(first.path("id"));
        var account=get("/accounts?studentId="+child,parent).get(0);assertThat(account.path("balance").decimalValue()).isEqualByComparingTo("3");assertThat(account.path("gifted").decimalValue()).isEqualByComparingTo("3");assertThat(get("/renewals?studentId="+child,teacher).isEmpty()).isTrue();
        var changed=new HashMap<String,Object>(body);changed.put("units",4);call(HttpMethod.POST,"/lesson-grants",teacher,changed,409);changed.put("studentId",otherChild);changed.put("requestId",key());call(HttpMethod.POST,"/lesson-grants",teacher,changed,403);call(HttpMethod.POST,"/lesson-grants",parent,body,403);
    }
    @Test void fixedAwardsUseServerRuleAndRetryIsIdempotent(){
        var preset=post("/point-presets",principal,Map.of("campusId",campus,"name","虚构主动思考","points",3));long id=preset.path("id").asLong();var body=Map.of("studentId",child,"presetId",id,"amount",999,"requestId",key());post("/point-awards",teacher,body);post("/point-awards",teacher,body);assertThat(db.one("SELECT points FROM student WHERE id=?",child).get("points")).isEqualTo(13);
        call(HttpMethod.POST,"/point-presets",teacher,Map.of("campusId",campus,"name","越权","points",3),403);call(HttpMethod.POST,"/point-awards",parent,body,403);call(HttpMethod.POST,"/point-awards",teacher,Map.of("studentId",otherChild,"presetId",id,"requestId",key()),403);
    }
    @Test void redemptionsDeductPointsAndStockAtomicallyAndPreserveEvidence()throws Exception{
        var prize=post("/reward-prizes",principal,Map.of("campusId",campus,"name","虚构文具","pointsCost",3,"stock",1));long id=prize.path("id").asLong(),photo=image(teacherId,campus);var body=Map.of("studentId",child,"prizeId",id,"expectedVersion",1,"attachmentIds",List.of(photo),"requestId",key());var redeemed=post("/prize-redemptions",teacher,body);assertThat(post("/prize-redemptions",teacher,body).path("id")).isEqualTo(redeemed.path("id"));
        assertThat(db.one("SELECT points FROM student WHERE id=?",child).get("points")).isEqualTo(7);assertThat(db.one("SELECT stock FROM reward_prize WHERE id=?",id).get("stock")).isEqualTo(0);assertThat(get("/prize-redemptions?studentId="+child,parent).get(0).path("attachments").size()).isEqualTo(1);
        var next=new HashMap<String,Object>(body);next.put("requestId",key());next.put("expectedVersion",2);call(HttpMethod.POST,"/prize-redemptions",teacher,next,400);call(HttpMethod.POST,"/prize-redemptions",parent,body,403);call(HttpMethod.GET,"/prize-redemptions?studentId="+child,stranger,null,403);
        call(HttpMethod.POST,"/reward-prizes",principal,Map.of("campusId",campus,"name","不可复用学生照片","pointsCost",1,"stock",1,"imageFileId",photo),400);
    }
    @Test void concurrentRedemptionCannotOverdrawPointsOrStock(){
        var prize=post("/reward-prizes",principal,Map.of("campusId",campus,"name","虚构唯一奖品","pointsCost",8,"stock",1));long id=prize.path("id").asLong();var requests=new ArrayList<CompletableFuture<Integer>>();
        for(int i=0;i<2;i++)requests.add(CompletableFuture.supplyAsync(()->{var headers=new HttpHeaders();headers.setBearerAuth(teacher);headers.setContentType(MediaType.APPLICATION_JSON);return http.exchange("/api/prize-redemptions",HttpMethod.POST,new HttpEntity<>(Map.of("studentId",child,"prizeId",id,"expectedVersion",1,"requestId",key()),headers),String.class).getStatusCode().value();}));
        var codes=requests.stream().map(CompletableFuture::join).toList();assertThat(codes.stream().filter(code->code==200).count()).isEqualTo(1);assertThat(codes).allMatch(code->code==200||code==409||code==400);assertThat(db.one("SELECT points FROM student WHERE id=?",child).get("points")).isEqualTo(2);assertThat(db.one("SELECT stock FROM reward_prize WHERE id=?",id).get("stock")).isEqualTo(0);
    }
    @Test void summaryPublicationAwardsConfiguredPointsOnlyOnce(){
        post("/summaries/generate",teacher,Map.of("studentIds",List.of(child),"date",sec.today().toString()));var summary=get("/summaries?studentId="+child,teacher).get(0);long id=summary.path("id").asLong();summary=post("/summaries/"+id+"/submit-review",teacher,Map.of("expectedVersion",summary.path("version").asInt()));summary=post("/summaries/"+id+"/review",principal,Map.of("expectedVersion",summary.path("version").asInt(),"approved",true,"comment","虚构核对通过"));post("/summaries/"+id+"/publish",teacher,Map.of());post("/summaries/"+id+"/publish",teacher,Map.of());
        assertThat(db.count("SELECT COUNT(*) FROM point_ledger WHERE student_id=? AND type='SUMMARY_PUBLISHED'",child)).isEqualTo(1);assertThat(db.one("SELECT points FROM student WHERE id=?",child).get("points")).isEqualTo(12);
    }
    @Test void capturedQuestionsNeedNoClassificationAndHomeworkUploadIsRetrySafe()throws Exception{
        long photo=image(teacherId,campus);assertThat(post("/teaching-capture/recognize",teacher,Map.of("studentId",child,"photoFileId",photo)).path("status").asText()).isEqualTo("NOT_CONFIGURED");
        var body=new HashMap<String,Object>(Map.of("studentId",child,"photoFileId",photo,"kind","MISTAKE","subject","数学","questionText","","requestId",key()));var mistake=post("/teaching-capture/save",teacher,body);assertThat(mistake.path("knowledgePoint").asText()).isEmpty();assertThat(mistake.path("attachments").size()).isEqualTo(1);assertThat(post("/teaching-capture/save",teacher,body).path("id")).isEqualTo(mistake.path("id"));
        body.put("kind","HOMEWORK");call(HttpMethod.POST,"/teaching-capture/save",teacher,body,409);body.put("requestId",key());body.put("questionText","计算12加8");var homework=post("/teaching-capture/save",teacher,body);assertThat(homework.path("status").asText()).isEqualTo("PENDING_REVIEW");assertThat(post("/teaching-capture/save",teacher,body).path("id")).isEqualTo(homework.path("id"));
    }
    @Test void printableBooksContainChineseQuestionAndPhotoAndRemainStudentScoped()throws Exception{
        long photo=image(teacherId,campus);post("/mistakes",teacher,Map.of("studentId",child,"subject","数学","questionText","小明有12颗糖，又得到8颗，一共有多少颗？","attachmentIds",List.of(photo)));
        var file=post("/mistake-books",teacher,Map.of("studentId",child));long id=file.path("id").asLong();var headers=new HttpHeaders();headers.setBearerAuth(parent);var response=http.exchange("/api/files/"+id,HttpMethod.GET,new HttpEntity<>(headers),byte[].class);assertThat(response.getStatusCode().value()).isEqualTo(200);
        try(var pdf=Loader.loadPDF(response.getBody())){assertThat(pdf.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(pdf)).contains("小明有12颗糖","订正与思考");assertThat(pdf.getPage(0).getResources().getXObjectNames()).isNotEmpty();}Files.write(Path.of("target/teacher-tools-sample.pdf"),response.getBody());
        call(HttpMethod.GET,"/files/"+id+"/access",stranger,null,403);call(HttpMethod.POST,"/mistake-books",stranger,Map.of("studentId",child),403);post("/privacy/consents",parent,Map.of("studentId",child,"scope","MEDIA","granted",false,"policyVersion",PrivacyService.POLICY_VERSION));call(HttpMethod.GET,"/files/"+id+"/access",parent,null,403);
    }
}
