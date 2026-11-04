package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true","spring.profiles.active=local","spring.datasource.url=jdbc:h2:mem:question_scan;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","app.upload-dir=./target/scan-uploads"})
class QuestionScanIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    @MockBean QuestionOcrClient ocr;
    String teacher,parent,boss,other; long child,outside,campus,teacherId; byte[] png;
    String key(){return UUID.randomUUID().toString();}
    JsonNode call(HttpMethod method,String path,String token,Object body,int status){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try{return json.readTree(response.getBody());}catch(Exception error){throw new AssertionError(error);}
    }
    JsonNode get(String path,String token){return call(HttpMethod.GET,path,token,null,200);}
    JsonNode post(String path,String token,Object body){return call(HttpMethod.POST,path,token,body,200);}
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",key(),"unused-fixture-hash","虚构识题验收"+role,role);}
    String token(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    long student(long classroom){return db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('虚构识题学生','男','三年级',?,'虚构家长','13800000000')",classroom);}
    long image(long owner,long location)throws Exception{
        Path root=Path.of("target/scan-uploads");Files.createDirectories(root);String name=key();Files.write(root.resolve(name),png);
        return db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,'fictional-scan.png',?,'image/png',?,?,?)",owner,name,png.length,sec.now(),location);
    }
    void consent(boolean granted){post("/privacy/consents",parent,Map.of("studentId",child,"scope","MEDIA","granted",granted,"policyVersion",PrivacyService.POLICY_VERSION));}
    Map<String,Object> result(){return Map.of("engine","fixture-ocr","pageImage",Base64.getEncoder().encodeToString(png),"questions",List.of(
        Map.of("number","1","group","计算","text","虚构题干甲","regions",List.of(Map.of("x",.05,"y",.05,"width",.9,"height",.4)),"warnings",List.of()),
        Map.of("number","2","group","计算","text","虚构题干乙","regions",List.of(Map.of("x",.05,"y",.5,"width",.9,"height",.4)),"warnings",List.of("核对图形"))));}
    Map<String,Object> body(long photo){return Map.of("studentId",child,"photoFileId",photo,"subject","数学","requestId",key());}
    JsonNode create(Map<String,Object> body){return call(HttpMethod.POST,"/teaching-capture/jobs",teacher,body,202);}
    JsonNode await(long id,String state)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);JsonNode row=null;
        do{row=get("/teaching-capture/jobs/"+id,teacher);if(row.path("status").asText().equals(state))return row;Thread.sleep(60);}while(System.nanoTime()<deadline);
        throw new AssertionError("等待"+state+"，实际为"+row);
    }
    JsonNode ready()throws Exception{return await(create(body(image(teacherId,campus))).path("id").asLong(),"READY");}
    Map<String,Object> editBody(JsonNode job){return Map.of("expectedVersion",job.path("version").asInt(),"questions",json.convertValue(job.path("questions"),List.class));}
    JsonNode patch(JsonNode job,Object body,int code){return call(HttpMethod.PATCH,"/teaching-capture/jobs/"+job.path("id").asLong()+"/questions",teacher,body,code);}
    @BeforeEach void setup()throws Exception{
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        BufferedImage page=new BufferedImage(800,1000,BufferedImage.TYPE_INT_RGB);var g=page.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,800,1000);g.setColor(java.awt.Color.BLUE);g.fillRect(60,80,220,80);g.setColor(java.awt.Color.RED);g.fillRect(60,580,220,80);g.dispose();var output=new ByteArrayOutputStream();ImageIO.write(page,"png",output);png=output.toByteArray();
        campus=db.insert("INSERT INTO campus(name) VALUES('虚构识题校区')");long cl=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('识题班','虚构老师',?)",campus),otherClass=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('其他班','其他老师',?)",campus);
        child=student(cl);outside=student(otherClass);teacherId=user("TEACHER");long parentId=user("PARENT"),otherId=user("TEACHER");db.update("INSERT INTO teacher_class VALUES(?,?)",teacherId,cl);db.update("INSERT INTO teacher_class VALUES(?,?)",otherId,otherClass);db.update("INSERT INTO parent_student VALUES(?,?)",parentId,child);
        teacher=token(teacherId);parent=token(parentId);other=token(otherId);boss=post("/auth/demo",null,Map.of("role","ADMIN")).path("token").asText();
        for(String scope:List.of("SERVICE","MEDIA"))post("/privacy/consents",parent,Map.of("studentId",child,"scope",scope,"granted",true,"policyVersion",PrivacyService.POLICY_VERSION));
        when(ocr.configured()).thenReturn(true);when(ocr.status()).thenReturn(Map.of("available",true));when(ocr.recognize(any(byte[].class))).thenAnswer(invocation->result());
    }
    @Test void scanIsIdempotentAndOnlyExplicitlySelectedQuestionIsImportedAndPrinted()throws Exception{
        var body=body(image(teacherId,campus));var created=create(body);assertThat(create(body).path("id")).isEqualTo(created.path("id"));var changed=new HashMap<>(body);changed.put("subject","语文");call(HttpMethod.POST,"/teaching-capture/jobs",teacher,changed,409);
        var job=await(created.path("id").asLong(),"READY");assertThat(job.path("questions").size()).isEqualTo(2);assertThat(job.toString()).doesNotContain("sessionHash",teacher,"storageKey");
        long id=job.path("id").asLong(),qid=job.path("questions").get(1).path("id").asLong();var selected=Map.of("expectedVersion",job.path("version").asInt(),"questionIds",List.of(qid));
        var first=post("/teaching-capture/jobs/"+id+"/import",teacher,selected);var retry=post("/teaching-capture/jobs/"+id+"/import",teacher,selected);
        assertThat(first.path("results").get(0).path("mistakeId")).isEqualTo(retry.path("results").get(0).path("mistakeId"));assertThat(db.count("SELECT COUNT(*) FROM mistake WHERE student_id=?",child)).isEqualTo(1);
        long mistake=first.path("results").get(0).path("mistakeId").asLong();var row=get("/mistakes/"+mistake,parent);assertThat(row.path("questionText").asText()).isEqualTo("虚构题干乙");assertThat(row.path("attachments").size()).isEqualTo(1);
        long file=row.path("attachments").get(0).path("id").asLong();String storage=db.one("SELECT storage_key FROM stored_file WHERE id=?",file).get("storageKey").toString();var crop=ImageIO.read(Path.of("target/scan-uploads",storage).toFile());assertThat(crop.getWidth()).isBetween(220,720);assertThat(crop.getHeight()).isLessThan(150);assertThat(crop.getRGB(20,20)&0xffffff).isEqualTo(0xff0000);
        var book=post("/mistake-books",teacher,Map.of("studentId",child,"mistakeIds",List.of(mistake)));var headers=new HttpHeaders();headers.setBearerAuth(parent);var pdf=http.exchange("/api/files/"+book.path("id").asLong(),HttpMethod.GET,new HttpEntity<>(headers),byte[].class);
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);try(var doc=Loader.loadPDF(pdf.getBody())){assertThat(doc.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(doc)).doesNotContain("虚构题干乙");assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();}
    }
    @Test void editSupportsManualFramesAndRejectsStaleInvalidOrForeignCandidates()throws Exception{
        var job=ready();var rows=(com.fasterxml.jackson.databind.node.ArrayNode)job.path("questions");
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("questionText","老师已校对");var saved=patch(job,editBody(job),200);assertThat(saved.path("version").asInt()).isEqualTo(job.path("version").asInt()+1);patch(job,editBody(job),409);
        var invalid=saved.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("questions").get(0).path("regions").get(0)).put("width",2);patch(saved,editBody(invalid),400);
        var extra=saved.path("questions").get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)extra).remove("id");((com.fasterxml.jackson.databind.node.ObjectNode)extra).put("displayNumber","补1");((com.fasterxml.jackson.databind.node.ArrayNode)saved.path("questions")).add(extra);
        var manual=patch(saved,editBody(saved),200);assertThat(manual.path("questions").size()).isEqualTo(3);assertThat(db.one("SELECT recognition_text FROM question_scan_item WHERE id=?",job.path("questions").get(0).path("id").asLong()).get("recognitionText")).isEqualTo("虚构题干甲");
        ((com.fasterxml.jackson.databind.node.ObjectNode)manual.path("questions").get(0)).put("id",Long.MAX_VALUE);patch(manual,editBody(manual),403);
    }
    @Test void importedCandidateCannotBeChangedRemovedOrImportedFromAnotherScan()throws Exception{
        var job=ready();long id=job.path("id").asLong(),qid=job.path("questions").get(0).path("id").asLong();post("/teaching-capture/jobs/"+id+"/import",teacher,Map.of("expectedVersion",1,"questionIds",List.of(qid)));
        job=get("/teaching-capture/jobs/"+id,teacher);var unchanged=patch(job,editBody(job),200);var changed=unchanged.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("questions").get(0)).put("questionText","不能覆盖已入库题");patch(changed,editBody(changed),409);
        var removed=unchanged.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)removed.path("questions")).remove(0);patch(removed,editBody(removed),409);
        var second=ready();call(HttpMethod.POST,"/teaching-capture/jobs/"+second.path("id").asLong()+"/import",teacher,Map.of("expectedVersion",1,"questionIds",List.of(qid)),403);
    }
    @Test void parentsOtherClassesAndForeignCampusImagesCannotScanOrReadTasks()throws Exception{
        long photo=image(teacherId,campus);var body=body(photo);call(HttpMethod.POST,"/teaching-capture/jobs",parent,body,403);call(HttpMethod.POST,"/teaching-capture/jobs",other,body,403);
        var foreign=new HashMap<>(body);foreign.put("studentId",outside);call(HttpMethod.POST,"/teaching-capture/jobs",teacher,foreign,403);
        long otherCampus=db.insert("INSERT INTO campus(name) VALUES('虚构外校')");foreign=new HashMap<>(body);foreign.put("photoFileId",image(teacherId,otherCampus));call(HttpMethod.POST,"/teaching-capture/jobs",teacher,foreign,403);
        var job=ready();for(String token:List.of(parent,other))call(HttpMethod.GET,"/teaching-capture/jobs/"+job.path("id").asLong(),token,null,403);
        call(HttpMethod.GET,"/teaching-capture/jobs?studentId="+child,other,null,403);
    }
    @Test void unavailableOrFailedOcrKeepsOriginalAndCanBeRetriedWithoutFakeQuestions()throws Exception{
        when(ocr.configured()).thenReturn(false);call(HttpMethod.POST,"/teaching-capture/jobs",teacher,body(image(teacherId,campus)),503);when(ocr.configured()).thenReturn(true);
        when(ocr.recognize(any(byte[].class))).thenThrow(new ApiException(503,"虚构OCR停机"));var created=create(body(image(teacherId,campus)));var failed=await(created.path("id").asLong(),"FAILED");assertThat(failed.path("questions")).isEmpty();assertThat(failed.path("sourcePhoto").path("id").asLong()).isPositive();
        when(ocr.recognize(any(byte[].class))).thenAnswer(invocation->result());post("/teaching-capture/jobs/"+created.path("id").asLong()+"/retry",teacher,Map.of());var recovered=await(created.path("id").asLong(),"READY");assertThat(recovered.path("questions").size()).isEqualTo(2);
    }
    @Test void withdrawalDuringRecognitionPreventsStoringOrReadingResults()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(ocr.recognize(any(byte[].class))).thenAnswer(invocation->{entered.countDown();assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();return result();});
        var created=create(body(image(teacherId,campus)));long id=created.path("id").asLong();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();consent(false);release.countDown();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(db.one("SELECT status FROM question_scan WHERE id=?",id).get("status").equals("RECOGNIZING")&&System.nanoTime()<deadline)Thread.sleep(40);
        assertThat(db.one("SELECT status,page_file_id FROM question_scan WHERE id=?",id).get("status")).isEqualTo("FAILED");assertThat(db.count("SELECT COUNT(*) FROM question_scan_item WHERE scan_id=?",id)).isZero();call(HttpMethod.GET,"/teaching-capture/jobs/"+id,teacher,null,403);
    }
    @Test void expiredLoginCannotCompleteBackgroundRecognition()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(ocr.recognize(any(byte[].class))).thenAnswer(invocation->{entered.countDown();assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();return result();});
        long id=create(body(image(teacherId,campus))).path("id").asLong();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();db.update("DELETE FROM app_session WHERE token_hash=?",sec.hash(teacher));release.countDown();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(db.one("SELECT status FROM question_scan WHERE id=?",id).get("status").equals("RECOGNIZING")&&System.nanoTime()<deadline)Thread.sleep(40);
        assertThat(db.one("SELECT status FROM question_scan WHERE id=?",id).get("status")).isEqualTo("FAILED");assertThat(db.count("SELECT COUNT(*) FROM question_scan_item WHERE scan_id=?",id)).isZero();
    }
    @Test void privacyExportAndErasureIncludeScanTextAndBlockExistingImageTickets()throws Exception{
        var job=ready();String url=job.path("pagePhoto").path("url").asText();long export=post("/privacy/requests",parent,Map.of("studentId",child,"type","EXPORT","reason","虚构资料副本")).path("id").asLong();post("/privacy/requests/"+export+"/review",boss,Map.of("decision","APPROVE","note","虚构核对"));var data=post("/privacy/requests/"+export+"/export",parent,Map.of());assertThat(data.path("scanQuestions").size()).isEqualTo(2);assertThat(data.toString()).doesNotContain(sec.hash(teacher),"sessionHash");
        long erasure=post("/privacy/requests",parent,Map.of("studentId",child,"type","ERASURE","reason","虚构脱敏")).path("id").asLong();post("/privacy/requests/"+erasure+"/review",boss,Map.of("decision","APPROVE","note","虚构核对"));post("/privacy/requests/"+erasure+"/erase",boss,Map.of("confirmed",true,"note","虚构清理"));
        assertThat(db.list("SELECT question_text,recognition_text FROM question_scan_item WHERE scan_id=?",job.path("id").asLong()).toString()).doesNotContain("虚构题干");assertThat(db.one("SELECT session_hash FROM question_scan WHERE id=?",job.path("id").asLong()).get("sessionHash")).isEqualTo("");assertThat(http.getForEntity(url,byte[].class).getStatusCode().value()).isIn(401,403);
    }
    @Test void reusedSourcePhotoTracksEveryStudentConsentIndependently()throws Exception{
        long cl=Db.id(db.one("SELECT class_id FROM student WHERE id=?",child),"classId"),second=student(cl),parentId=sec.authenticate(parent).id();
        db.update("INSERT INTO parent_student VALUES(?,?)",parentId,second);
        for(String scope:List.of("SERVICE","MEDIA"))post("/privacy/consents",parent,Map.of("studentId",second,"scope",scope,"granted",true,"policyVersion",PrivacyService.POLICY_VERSION));
        long photo=image(teacherId,campus);var first=await(create(body(photo)).path("id").asLong(),"READY");
        var secondBody=new HashMap<>(body(photo));secondBody.put("studentId",second);await(create(secondBody).path("id").asLong(),"READY");
        assertThat(db.count("SELECT COUNT(DISTINCT student_id) FROM file_link WHERE file_id=? AND kind='SCAN_SOURCE'",photo)).isEqualTo(2);
        String ticket=first.path("sourcePhoto").path("url").asText();post("/privacy/consents",parent,Map.of("studentId",second,"scope","MEDIA","granted",false,"policyVersion",PrivacyService.POLICY_VERSION));
        call(HttpMethod.GET,"/files/"+photo+"/access",teacher,null,403);assertThat(http.getForEntity(ticket,byte[].class).getStatusCode().value()).isEqualTo(403);
    }
    @Test void bonusQuestionKeepsItsHeadingInMistakeTitleAndImageFilename()throws Exception{
        when(ocr.recognize(any(byte[].class))).thenReturn(Map.of("engine","fixture-fast-locator","pageImage",Base64.getEncoder().encodeToString(png),"questions",List.of(Map.of("number","附加题","group","附加题","text","","regions",List.of(Map.of("x",.05,"y",.05,"width",.9,"height",.8)),"warnings",List.of()))));
        var job=ready();long qid=job.path("questions").get(0).path("id").asLong();var result=post("/teaching-capture/jobs/"+job.path("id").asLong()+"/import",teacher,Map.of("expectedVersion",1,"questionIds",List.of(qid)));
        var mistake=get("/mistakes/"+result.path("results").get(0).path("mistakeId").asLong(),parent);assertThat(mistake.path("title").asText()).isEqualTo("附加题");assertThat(mistake.path("questionText").asText()).isEqualTo("附加题");assertThat(mistake.path("attachments").get(0).path("name").asText()).isEqualTo("附加题.png");
    }
    @Test void printedTitleAndBodyCanBeCorrectedAndAreKeptInTheMistakeBook()throws Exception{
        when(ocr.recognize(any(byte[].class))).thenReturn(Map.of("engine","fixture-printed-ocr","pageImage",Base64.getEncoder().encodeToString(png),"questions",List.of(Map.of("number","4","group","虚构印刷标题","text","4. 虚构印刷题干\n虚构价目表","regions",List.of(Map.of("x",.05,"y",.05,"width",.9,"height",.8)),"warnings",List.of()))));
        var job=ready();assertThat(job.path("questions").get(0).path("groupName").asText()).isEqualTo("虚构印刷标题");assertThat(job.path("questions").get(0).path("questionText").asText()).contains("虚构价目表");
        ((com.fasterxml.jackson.databind.node.ObjectNode)job.path("questions").get(0)).put("groupName","老师校对标题").put("questionText","老师校对印刷题干");var saved=patch(job,editBody(job),200);long qid=saved.path("questions").get(0).path("id").asLong();
        var imported=post("/teaching-capture/jobs/"+saved.path("id").asLong()+"/import",teacher,Map.of("expectedVersion",saved.path("version").asInt(),"questionIds",List.of(qid)));
        var mistake=get("/mistakes/"+imported.path("results").get(0).path("mistakeId").asLong(),parent);assertThat(mistake.path("title").asText()).isEqualTo("老师校对标题");assertThat(mistake.path("questionText").asText()).isEqualTo("老师校对印刷题干");assertThat(mistake.path("attachments").get(0).path("name").asText()).isEqualTo("第4题.png");
    }
}
