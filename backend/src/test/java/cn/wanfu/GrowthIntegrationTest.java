package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true",
    "spring.datasource.url=jdbc:h2:mem:growth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/growth-uploads"})
@ActiveProfiles("local")
class GrowthIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String teacher,parent,boss,principal,date;
    static final java.util.concurrent.atomic.AtomicInteger day=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeEach void login() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        teacher=demo("TEACHER"); parent=demo("PARENT"); boss=demo("ADMIN"); principal=demo("CAMPUS_ADMIN");
        for(var student:get("/me",parent).path("studentIds")) grantMedia(parent,student.asLong());
        date=sec.today().minusDays(20+day.getAndIncrement()).toString();
    }
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        if(method==HttpMethod.POST && status==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,sec,body);
        HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); if(token!=null) h.setBearerAuth(token);
        var r=http.exchange("/api"+path,method,new HttpEntity<>(body,h),String.class);
        assertThat(r.getStatusCode().value()).as(method+" "+path+": "+r.getBody()).isEqualTo(status);
        try { return json.readTree(r.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    String demo(String role) { return call(HttpMethod.POST,"/auth/demo",null,Map.of("role",role),200).path("token").asText(); }
    JsonNode get(String p,String t) { return call(HttpMethod.GET,p,t,null,200); }
    JsonNode post(String p,String t,Object b) { return call(HttpMethod.POST,p,t,b,200); }
    String key() { return UUID.randomUUID().toString(); }
    void grantMedia(String token,long student) {
        String version=get("/privacy/policy",token).path("version").asText();
        for(String scope:List.of("SERVICE","MEDIA")) post("/privacy/consents",token,Map.of("studentId",student,"scope",scope,"policyVersion",version,"granted",true));
    }
    List<JsonNode> tasks(String taskDate,List<Long> students) {
        long id=post("/homework/tasks",teacher,Map.of("date",taskDate,"subject","数学","type","WRITTEN","title","验收计算练习","studentIds",students)).path("taskId").asLong();
        List<JsonNode> out=new ArrayList<>(); for(var h:get("/homework?date="+taskDate,teacher)) if(h.path("taskId").asLong()==id) out.add(h); return out;
    }
    Map<String,Object> issue(long sid) {
        return new LinkedHashMap<>(Map.of("studentId",sid,"date",date,"kind","ISSUE","type","LEARNING","title","需要辅导","reason","计算用时较长","measures","老师分步指导","needsParent",true,"parentAction","回家注意休息","requestId",key()));
    }
    JsonNode status(JsonNode issue,String target,String token) {
        return call(HttpMethod.PUT,"/follow-ups/"+issue.path("id").asLong()+"/status",token,Map.of("status",target,"text","验收：已核对处理情况","expectedVersion",issue.path("version").asInt(),"requestId",key()),200);
    }
    record MediaFixture(String name,String contentType,byte[] bytes) {}
    static MediaFixture fixture(String name,String contentType,String base64) { return new MediaFixture(name,contentType,Base64.getDecoder().decode(base64)); }
    static final MediaFixture PNG=fixture("fictional-practice.png","image/png","iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aUe8AAAAASUVORK5CYII=");
    // Synthetic silence and a white frame keep media transfer tests offline and free of student data.
    static final MediaFixture MP3=fixture("fictional-reading.mp3","audio/mpeg","SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYyLjEyLjEwMAAAAAAAAAAAAAAA/+MYxAAAAANIAAAAAExBTUUzLjEwMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxDsAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxHYAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxLEAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV");
    static final MediaFixture MP4=fixture("fictional-exercise.mp4","video/mp4","AAAAJGZ0eXBpc29tAAACAGlzb21pc282aXNvMmF2YzFtcDQxAAAC5W1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAAA+gAAAAAAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAHndHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAQAAAAEAAAAAABg21kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAAQAAAAAAAVcQAAAAAAC1oZGxyAAAAAAAAAAB2aWRlAAAAAAAAAAAAAAAAVmlkZW9IYW5kbGVyAAAAAS5taW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAADuc3RibAAAAKJzdHNkAAAAAAAAAAEAAACSYXZjMQAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAQABAASAAAAEgAAAAAAAAAARVMYXZjNjIuMjguMTAwIGxpYngyNjQAAAAAAAAAAAAAABj//wAAACxhdmNDAULACv/hABVnQsAK2nsBEAAAAwAQAAADACDxImoBAARozg/IAAAAEHBhc3AAAAABAAAAAQAAABBzdHRzAAAAAAAAAAAAAAAQc3RzYwAAAAAAAAAAAAAAFHN0c3oAAAAAAAAAAAAAAAAAAAAQc3RjbwAAAAAAAAAAAAAAKG12ZXgAAAAgdHJleAAAAAAAAAABAAAAAQAAAAAAAAAAAAAAAAAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjIuMTIuMTAwAAAAcG1vb2YAAAAQbWZoZAAAAAAAAAABAAAAWHRyYWYAAAAkdGZoZAAAADkAAAABAAAAAAAAAwkAAEAAAAACZQEBAAAAAAAUdGZkdAEAAAAAAAAAAAAAAAAAABh0cnVuAAAABQAAAAEAAAB4AgAAAAAAAm1tZGF0AAACUwYF//9P3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NSByMzIyMiBiMzU2MDVhIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyNSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTAgcmVmPTEgZGVibG9jaz0wOjA6MCBhbmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0wIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0wIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj0xIHNjZW5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0wAIAAAAAKZYiEOiYoAAj2YAAAAENtZnJhAAAAK3RmcmEBAAAAAAAAAQAAAAAAAAABAAAAAAAAAAAAAAAAAAADCQEBAQAAABBtZnJvAAAAAAAAAEM=");
    long upload(String token,MediaFixture fixture) {
        HttpHeaders headers=new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.MULTIPART_FORM_DATA); headers.set("X-Campus-Id","1");
        HttpHeaders fileHeaders=new HttpHeaders(); fileHeaders.setContentType(MediaType.parseMediaType(fixture.contentType()));
        var resource=new ByteArrayResource(fixture.bytes()) { @Override public String getFilename() { return fixture.name(); } };
        MultiValueMap<String,Object> body=new LinkedMultiValueMap<>(); body.add("file",new HttpEntity<>(resource,fileHeaders));
        var response=http.exchange("/api/files",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var file=Objects.requireNonNull(response.getBody());
        assertThat(file.path("contentType").asText()).isEqualTo(fixture.contentType());
        assertThat(file.path("size").asLong()).isEqualTo(fixture.bytes().length);
        return file.path("id").asLong();
    }
    void download(String token,long id,MediaFixture fixture) {
        String url=get("/files/"+id+"/access",token).path("url").asText();
        assertThat(url).contains("?ticket=").doesNotContain(token).doesNotContain("?token=");
        var response=http.getForEntity(url,byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(fixture.contentType()));
        assertThat(response.getBody()).containsExactly(fixture.bytes());
    }
    long homework(String subject,String type,List<Long> attachments) {
        long task=post("/homework/tasks",teacher,Map.of("date",date,"subject",subject,"type",type,"title","虚构验收作业","studentIds",List.of(1L),"attachmentIds",attachments)).path("taskId").asLong();
        for(var item:get("/homework?date="+date+"&studentId=1",teacher)) if(item.path("taskId").asLong()==task) return item.path("id").asLong();
        throw new AssertionError("创建后的作业记录缺失");
    }
    List<Long> attachmentIds(JsonNode attachments) {
        List<Long> ids=new ArrayList<>(); for(var file:attachments) ids.add(file.path("id").asLong()); return ids;
    }
    long correctedMistake() {
        long id=post("/mistakes",teacher,Map.of("studentId",1,"subject","数学","title","虚构错题","knowledgePoint","进位","errorReason","漏写进位")).path("id").asLong();
        post("/mistakes/"+id+"/correct",parent,Map.of("text","逐步列式并验算")); return id;
    }
    @Test void summaryPhotoRemainsPrivateUntilIndependentReviewAndPublication() {
        long photo=upload(teacher,PNG);
        post("/summaries/generate",teacher,Map.of("studentIds",List.of(1),"date",date,"keepExisting",true));
        var draft=get("/summaries?studentId=1&date="+date,teacher).get(0);
        String path="/summaries/"+draft.path("id").asLong();
        var edited=call(HttpMethod.PATCH,path,teacher,Map.of("expectedVersion",draft.path("version").asInt(),"teacherComment","今天课堂主动参与","photoId",photo),200);
        assertThat(attachmentIds(edited.path("photos"))).containsExactly(photo);
        call(HttpMethod.GET,"/files/"+photo+"/access",parent,null,403);
        assertThat(get("/summaries?studentId=1&date="+date,parent)).isEmpty();
        assertThat(post("/summaries/generate",teacher,Map.of("studentIds",List.of(1),"date",date,"keepExisting",true)).path("generatedCount").asInt()).isZero();
        var submitted=post(path+"/submit-review",teacher,Map.of("expectedVersion",edited.path("version").asInt()));
        call(HttpMethod.PATCH,path,teacher,Map.of("expectedVersion",submitted.path("version").asInt(),"photoId",photo),400);
        assertThat(attachmentIds(get(path,principal).path("photos"))).containsExactly(photo);
        post(path+"/review",principal,Map.of("expectedVersion",submitted.path("version").asInt(),"approved",true,"comment","作品和文字已核对"));
        call(HttpMethod.GET,"/files/"+photo+"/access",parent,null,403);
        post(path+"/publish",teacher,Map.of());
        assertThat(attachmentIds(get(path,parent).path("photos"))).containsExactly(photo);
        download(parent,photo,PNG);
    }
    @Test void comparisonsUseOnlyTheSelectedChildExistingWorkAndKeepOrder() {
        long before=upload(teacher,PNG),after=upload(teacher,PNG),unlinked=upload(teacher,PNG);
        homework("数学","WRITTEN",List.of(before));
        post("/mistakes",teacher,Map.of("studentId",1,"subject","数学","title","虚构近期作品","attachmentIds",List.of(after)));
        assertThat(get("/growth/comparison-works?studentId=1",teacher).findValues("id")).extracting(JsonNode::asLong).contains(before,after).doesNotContain(unlinked);
        call(HttpMethod.GET,"/growth/comparison-works?studentId=1",parent,null,403);
        var body=new LinkedHashMap<String,Object>(Map.of("studentId",1,"beforeId",before,"afterId",unlinked,"text","能独立完成","requestId",key()));
        call(HttpMethod.POST,"/growth/comparisons",teacher,body,403);
        body.put("afterId",after);
        var created=post("/growth/comparisons",teacher,body);
        assertThat(post("/growth/comparisons",teacher,body).path("id")).isEqualTo(created.path("id"));
        var history=get("/growth?studentId=1&date="+sec.today(),parent).path("timeline");
        var event=java.util.stream.StreamSupport.stream(history.spliterator(),false).filter(row->row.path("action").asText().equals("COMPARISON")&&row.path("id").equals(created.path("id"))).findFirst().orElseThrow();
        assertThat(attachmentIds(event.path("before"))).containsExactly(before);
        assertThat(attachmentIds(event.path("after"))).containsExactly(after);
        call(HttpMethod.POST,"/growth/comparisons",parent,body,403);
    }
    @Test void tomorrowReminderUsesTheExistingPrivateMessage() {
        var body=new LinkedHashMap<String,Object>(Map.of("studentId",1,"date",sec.today().toString(),"kind","MESSAGE","reason","明天带英语练习册","tomorrowReminder",true,"requestId",key()));
        var reminder=post("/follow-ups",teacher,body);
        assertThat(reminder.path("type").asText()).isEqualTo("REMINDER");
        assertThat(get("/follow-ups/"+reminder.path("id").asLong(),parent).path("reason").asText()).isEqualTo("明天带英语练习册");
        call(HttpMethod.POST,"/follow-ups",parent,body,403);
        body.put("date",date); body.put("requestId",key());
        call(HttpMethod.POST,"/follow-ups",teacher,body,400);
    }
    @Test void delayedMistakeApprovalSchedulesReviewsFromApprovalDay() {
        long id=correctedMistake();
        db.update("UPDATE mistake SET created_at=? WHERE id=?",sec.now().minusDays(30),id);
        var approved=post("/mistakes/"+id+"/review",teacher,Map.of("approved",true,"comment","订正正确"));
        List<String> dates=new ArrayList<>(); for(var review:approved.path("reviews")) dates.add(review.path("dueDate").asText());
        assertThat(dates).containsExactly(sec.today().plusDays(1).toString(),sec.today().plusDays(3).toString(),sec.today().plusDays(7).toString());
    }
    @Test void futureReviewIsRejectedAndDueCompletionIsIdempotent() {
        long mistake=correctedMistake();
        long correction=Db.id(db.one("SELECT id FROM correction WHERE mistake_id=? ORDER BY id DESC",mistake),"id");
        var correctionAudit=db.one("SELECT actor_id,actor_name,campus_id FROM audit_log WHERE action='MISTAKE_CORRECT' AND detail=?","mistake="+mistake+", correction="+correction);
        assertThat(Db.id(correctionAudit,"actorId")).isEqualTo(get("/me",parent).path("id").asLong());
        assertThat(Db.id(correctionAudit,"campusId")).isEqualTo(1L); assertThat(correctionAudit.get("actorName")).isNotNull();
        var approved=post("/mistakes/"+mistake+"/review",teacher,Map.of("approved",true,"comment","订正正确"));
        long review=approved.path("reviews").get(0).path("id").asLong(); String path="/mistakes/reviews/"+review+"/complete";
        call(HttpMethod.POST,path,teacher,Map.of(),400);
        assertThat(db.one("SELECT status,completed_at FROM mistake_review WHERE id=?",review)).containsEntry("status","PENDING").containsEntry("completedAt",null);
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='MISTAKE_REVIEW_COMPLETE' AND detail=?","review="+review)).isZero();
        db.update("UPDATE mistake_review SET due_date=? WHERE id=?",sec.today(),review);
        post(path,teacher,Map.of());
        var completed=db.one("SELECT status,completed_at FROM mistake_review WHERE id=?",review);
        assertThat(completed.get("status")).isEqualTo("DONE"); assertThat(completed.get("completedAt")).isNotNull();
        post(path,teacher,Map.of());
        assertThat(db.one("SELECT completed_at FROM mistake_review WHERE id=?",review).get("completedAt")).isEqualTo(completed.get("completedAt"));
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='MISTAKE_REVIEW_COMPLETE' AND detail=?","review="+review)).isEqualTo(1);
        var completedAudit=db.one("SELECT actor_id,campus_id FROM audit_log WHERE action='MISTAKE_REVIEW_COMPLETE' AND detail=?","review="+review);
        assertThat(Db.id(completedAudit,"actorId")).isEqualTo(get("/me",teacher).path("id").asLong()); assertThat(Db.id(completedAudit,"campusId")).isEqualTo(1L);
    }
    @Test void summaryLabelsUnconfirmedFitnessAndTotalsOnlyConfirmedDuration() {
        post("/fitness/batch",teacher,Map.of("date",date,"studentIds",List.of(1L),"project","虚构跳绳","count",100,"duration",60));
        db.insert("INSERT INTO fitness(student_id,date,project,count,duration,confirmed) VALUES(1,?,'虚构慢跑',20,600,FALSE)",date);
        post("/summaries/generate",teacher,Map.of("date",date,"studentIds",List.of(1L)));
        String content=get("/summaries?studentId=1&date="+date,teacher).get(0).path("content").asText();
        assertThat(content).contains("虚构跳绳 100次 / 60秒","虚构慢跑（待确认）","已确认合计：60秒");
    }
    @Test void teacherExampleAudioCannotReplaceParentOralSubmission() {
        long example=upload(teacher,MP3),id=homework("英语","ORAL",List.of(example)); String path="/homework/"+id;
        call(HttpMethod.POST,path+"/status",parent,Map.of("status","PENDING_REVIEW","submissionText","仅文字说明"),400);
        call(HttpMethod.POST,path+"/status",parent,Map.of("status","PENDING_REVIEW","attachmentIds",List.of(example)),400);
        assertThat(get(path,parent).path("status").asText()).isEqualTo("NOT_STARTED");
        long answer=upload(parent,MP3);
        var submitted=post(path+"/status",parent,Map.of("status","PENDING_REVIEW","submissionText","虚构学生朗读","attachmentIds",List.of(answer),"requestId",key()));
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(attachmentIds(submitted.path("history").get(0).path("attachments"))).containsExactly(answer);
        long guardian=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,'unused','虚构共同监护人','PARENT')","guardian-"+key());
        db.update("INSERT INTO parent_student(user_id,student_id) VALUES(?,1)",guardian);
        String shared=(String)sec.session(db.one("SELECT * FROM app_user WHERE id=?",guardian),false).get("token");
        grantMedia(shared,1);
        long original=get("/me",parent).path("id").asLong();
        db.update("DELETE FROM parent_student WHERE user_id=? AND student_id=1",original);
        try {
            var updated=post(path+"/status",shared,Map.of("status","PENDING_REVIEW","submissionText","共同监护人补充说明","requestId",key()));
            assertThat(updated.path("status").asText()).isEqualTo("PENDING_REVIEW");
            download(shared,answer,MP3);
        } finally { db.update("INSERT INTO parent_student(user_id,student_id) VALUES(?,1)",original); }
    }
    @Test void allSubjectsAndHomeworkTypesTransferPngMp3AndMp4OverHttp() {
        Map<MediaFixture,Long> uploaded=new LinkedHashMap<>(); for(var media:List.of(PNG,MP3,MP4)) uploaded.put(media,upload(parent,media));
        for(String subject:List.of("语文","数学","英语")) for(String type:List.of("WRITTEN","ORAL")) for(var media:uploaded.entrySet()) {
            long id=homework(subject,type,List.of()); List<Long> attachments=new ArrayList<>(List.of(media.getValue()));
            if(type.equals("ORAL")&&media.getKey()!=MP3) attachments.add(uploaded.get(MP3));
            post("/homework/"+id+"/status",parent,Map.of("status","PENDING_REVIEW","submissionText","虚构提交说明","attachmentIds",attachments,"expectedVersion",0,"requestId",key()));
            var detail=get("/homework/"+id,teacher);
            assertThat(detail.path("subject").asText()).isEqualTo(subject); assertThat(detail.path("type").asText()).isEqualTo(type);
            assertThat(detail.path("status").asText()).isEqualTo("PENDING_REVIEW");
            assertThat(attachmentIds(detail.path("attachments"))).containsExactlyInAnyOrderElementsOf(attachments);
            assertThat(attachmentIds(detail.path("history").get(0).path("attachments"))).containsExactlyInAnyOrderElementsOf(attachments);
            download(parent,media.getValue(),media.getKey()); download(teacher,media.getValue(),media.getKey());
        }
    }
    @Test void homeworkPreservesCorrectionHistoryAndRewardsOnceAcrossDays() {
        long id=tasks(date,List.of(1L)).get(0).path("id").asLong(); String path="/homework/"+id;
        call(HttpMethod.POST,path+"/review",parent,Map.of("status","COMPLETED","accuracy",100),403);
        var submit=Map.of("status","PENDING_REVIEW","submissionText","已完成","expectedVersion",0,"requestId",key());
        assertThat(post(path+"/status",parent,submit).path("status").asText()).isEqualTo("PENDING_REVIEW");
        post(path+"/status",parent,submit);
        call(HttpMethod.POST,path+"/review",teacher,Map.of("status","COMPLETED","accuracy",92,"expectedVersion",0),409);
        call(HttpMethod.POST,path+"/review",teacher,Map.of("status","CORRECTION","accuracy",80),400);
        var corrected=post(path+"/review",teacher,Map.of("status","CORRECTION","accuracy",80,"comment","第2题请检查单位","expectedVersion",1));
        assertThat(corrected.path("completedAt").isNull()).isTrue(); assertThat(corrected.path("reviewedAt").isTextual()).isTrue();
        call(HttpMethod.POST,path+"/status",parent,Map.of("status","PENDING_RECHECK"),400);
        long evidence=db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,? ,?,'image/png',1,?,1)",db.count("SELECT id FROM app_user WHERE username='parent'"),"仅数据库证据边界","test-"+key(),sec.now());
        post(path+"/attachments",parent,Map.of("fileIds",List.of(evidence)));
        call(HttpMethod.POST,path+"/status",parent,Map.of("status","PENDING_RECHECK","attachmentIds",List.of(evidence)),400);
        post(path+"/status",parent,Map.of("status","PENDING_RECHECK","submissionText","已重新换算单位","expectedVersion",2,"requestId",key()));
        long before=db.count("SELECT COUNT(*) FROM point_ledger WHERE event_key=?","homework:"+id);
        var review=Map.of("status","COMPLETED","accuracy",100,"comment","订正正确","expectedVersion",3,"requestId",key());
        var completed=post(path+"/review",teacher,review); post(path+"/review",teacher,review);
        assertThat(completed.path("history").size()).isEqualTo(4); assertThat(completed.path("completedAt").asText()).startsWith(sec.today().toString());
        assertThat(db.count("SELECT COUNT(*) FROM point_ledger WHERE event_key=?","homework:"+id)-before).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM growth_event WHERE source_type='HOMEWORK' AND source_id=?",id)).isEqualTo(4);
        var day=get("/growth?studentId=1&date="+date,parent); assertThat(day.path("homework").toString()).contains("COMPLETED");
        assertThat(day.path("pointsDelta").asInt()).isZero();
        assertThat(get("/growth?studentId=1&date="+sec.today(),parent).path("timeline").toString()).contains("已重新换算单位");
        long onsite=tasks(date,List.of(1L)).get(0).path("id").asLong();
        assertThat(post("/homework/"+onsite+"/review",teacher,Map.of("status","COMPLETED","accuracy",100,"comment","现场检查通过")).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void followupHasAppendOnlyRepliesReadReceiptsAndControlledTransitions() {
        var body=issue(1); var first=post("/follow-ups",teacher,body); long id=first.path("id").asLong(); String path="/follow-ups/"+id;
        var invalidTime=issue(1); invalidTime.put("nextFeedbackAt","今晚七点"); call(HttpMethod.POST,"/follow-ups",teacher,invalidTime,400);
        assertThat(post("/follow-ups",teacher,body).path("id").asLong()).isEqualTo(id);
        call(HttpMethod.POST,"/follow-ups",parent,issue(1),403);
        post("/follow-ups",teacher,issue(1));
        post("/follow-ups",parent,Map.of("studentId",1,"date",date,"kind","MESSAGE","reason","今天几点接孩子？","requestId",key()));
        assertThat(get("/growth?studentId=1&date="+date,parent).path("openConcernCount").asInt()).isEqualTo(2);
        var reply=Map.of("action","PARENT_DONE","text","家里已配合","requestId",key());
        var replied=post(path+"/reply",parent,reply); post(path+"/reply",parent,reply);
        assertThat(replied.path("status").asText()).isEqualTo("OPEN"); assertThat(replied.path("logs").size()).isEqualTo(2);
        assertThat(replied.path("logs").get(0).path("readByStaff").asBoolean()).isFalse();
        post(path+"/read",teacher,Map.of("lastLogId",replied.path("lastLogId").asLong()));
        assertThat(get(path,parent).path("logs").get(1).path("readByStaff").asBoolean()).isTrue();
        call(HttpMethod.PUT,path+"/status",parent,Map.of("status","RESOLVED","text","自行解决","expectedVersion",1,"requestId",key()),403);
        var processing=status(replied,"IN_PROGRESS",teacher); var resolved=status(processing,"RESOLVED",teacher);
        assertThat(get("/growth?studentId=1&date="+date,parent).path("openConcernCount").asInt()).isEqualTo(1);
        var reopened=status(resolved,"IN_PROGRESS",teacher); var cancelled=status(reopened,"CANCELLED",teacher);
        assertThat(cancelled.path("logs").size()).isEqualTo(6);
        call(HttpMethod.POST,path+"/reply",parent,Map.of("action","REPLY","text","再回复","requestId",key()),400);
        call(HttpMethod.PUT,path+"/owner",boss,Map.of("ownerId",2,"text","调整","requestId",key()),400);
    }
    @Test void batchPrevalidatesScopeThenReportsStaleItemsWithoutRepeatingAwards() {
        var items=tasks(date,List.of(1L,2L)); long a=items.get(0).path("id").asLong(),b=items.get(1).path("id").asLong(),tid=items.get(0).path("taskId").asLong();
        post("/homework/"+b+"/status",teacher,Map.of("status","IN_PROGRESS"));
        Map<String,Object> body=new LinkedHashMap<>(Map.of("taskId",tid,"status","COMPLETED","accuracy",95,"comment","批量核对","items",List.of(Map.of("id",a,"expectedVersion",0),Map.of("id",b,"expectedVersion",0)),"requestId",key()));
        var results=post("/homework/batch-review",teacher,body).path("results");
        assertThat(results.get(0).path("success").asBoolean()).isTrue(); assertThat(results.get(1).path("success").asBoolean()).isFalse();
        assertThat(results.get(1).path("status").asInt()).isEqualTo(409);
        var retry=post("/homework/batch-review",teacher,body).path("results"); assertThat(retry.get(0).path("success").asBoolean()).isTrue();
        assertThat(db.count("SELECT COUNT(*) FROM point_ledger WHERE event_key=?","homework:"+a)).isEqualTo(1);
        long foreignTask=db.insert("INSERT INTO homework_task(date,subject,type,title,description) VALUES(?,'数学','WRITTEN','另一班','')",date);
        long foreign=db.insert("INSERT INTO homework(task_id,student_id,status) VALUES(?,5,'NOT_STARTED')",foreignTask);
        body.put("items",List.of(Map.of("id",b,"expectedVersion",1),Map.of("id",foreign,"expectedVersion",0))); body.put("requestId",key());
        call(HttpMethod.POST,"/homework/batch-review",teacher,body,403);
        assertThat(get("/homework/"+b,teacher).path("status").asText()).isEqualTo("IN_PROGRESS");
        var mixed=tasks(date,List.of(1L,2L));
        var mixedResult=post("/homework/batch-review",teacher,Map.of("taskId",mixed.get(0).path("taskId").asLong(),"status","COMPLETED","accuracy",90,"items",List.of(Map.of("id",mixed.get(0).path("id").asLong(),"expectedVersion",0),Map.of("id",mixed.get(1).path("id").asLong(),"expectedVersion",0,"accuracy","95.5")),"requestId",key())).path("results");
        assertThat(mixedResult.get(0).path("success").asBoolean()).isTrue();
        assertThat(mixedResult.get(1).path("success").asBoolean()).isFalse();
        assertThat(mixedResult.get(1).path("status").asInt()).isEqualTo(400);
    }
    @Test void notesPauseAndLegacyDatesAreHonestAndFutureFactsAreRejected() {
        var note=Map.of("studentIds",List.of(1),"date",date,"type","COMMENT","text","认真整理书包","requestId",key());
        post("/growth/notes",teacher,note); post("/growth/notes",teacher,note);
        call(HttpMethod.POST,"/growth/notes",teacher,Map.of("studentIds",List.of(1),"date",date,"type","COMMENT","text","无效时间","occurredAt","不是日期","requestId",key()),400);
        long ledger=db.count("SELECT COUNT(*) FROM point_ledger WHERE student_id=1");
        post("/growth/fitness-pause",teacher,Map.of("studentIds",List.of(1),"date",date,"reason","身体不适","measures","安排休息","requestId",key()));
        var g=get("/growth?studentId=1&date="+date,parent);
        assertThat(g.path("fitnessPause").path("reason").asText()).isEqualTo("身体不适"); assertThat(g.path("openConcernCount").asInt()).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM point_ledger WHERE student_id=1")).isEqualTo(ledger);
        assertThat(g.path("timeline").get(0).path("occurredAt").isNull()).isTrue();
        assertThat(g.path("timeline").get(0).path("actorName").asText()).isNotBlank();
        String future=sec.today().plusDays(1).toString();
        call(HttpMethod.POST,"/growth/notes",teacher,Map.of("studentIds",List.of(1),"date",future,"type","COMMENT","text","未来事实","requestId",key()),400);
        call(HttpMethod.POST,"/fitness/batch",teacher,Map.of("studentIds",List.of(1),"date",future,"project","跑步","count",1,"duration",60),400);
        var attendance=post("/attendance/check-in",teacher,Map.of("studentIds",List.of(1),"date",future)); assertThat(attendance.path("results").get(0).path("success").asBoolean()).isFalse();
        long futureTask=tasks(future,List.of(1L)).get(0).path("id").asLong();
        call(HttpMethod.POST,"/homework/"+futureTask+"/review",teacher,Map.of("status","COMPLETED","accuracy",100),400);
        assertThat(get("/growth?date="+future,parent).path("readOnly").asBoolean()).isTrue();
    }
    @Test void publishedSummaryIsImmutableAndConcernsNeedPerStudentConfirmation() {
        post("/summaries/generate",teacher,Map.of("studentIds",List.of(1),"date",date));
        var summary=get("/summaries?studentId=1&date="+date,teacher).get(0); long id=summary.path("id").asLong();
        var concern=post("/follow-ups",teacher,issue(1));
        post("/summaries/generate",teacher,Map.of("studentIds",List.of(1),"date",date));
        summary=get("/summaries?studentId=1&date="+date,teacher).get(0);
        assertThat(summary.path("content").asText()).contains("需要辅导","老师分步指导");
        call(HttpMethod.POST,"/summaries/"+id+"/publish",teacher,null,400);
        assertThat(post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id))).path("results").get(0).path("success").asBoolean()).isFalse();
        var submitted=post("/summaries/"+id+"/submit-review",teacher,Map.of("expectedVersion",summary.path("version").asInt()));
        post("/summaries/"+id+"/review",principal,Map.of("expectedVersion",submitted.path("version").asInt(),"approved",true,"comment","已独立核对关注原因和处理措施"));
        call(HttpMethod.POST,"/summaries/"+id+"/publish",teacher,null,400);
        assertThat(post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id))).path("results").get(0).path("success").asBoolean()).isFalse();
        assertThat(post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id),"confirmedConcernStudentIds",List.of(1))).path("results").get(0).path("success").asBoolean()).isTrue();
        String snapshot=get("/summaries?studentId=1&date="+date,parent).get(0).path("content").asText();
        status(status(concern,"IN_PROGRESS",teacher),"RESOLVED",teacher);
        assertThat(get("/summaries?studentId=1&date="+date,parent).get(0).path("content").asText()).isEqualTo(snapshot);
    }
    @Test void rostersPermissionsAndConcurrentReviewsStayConsistent() {
        var fallback=get("/workbench?classId=1&date="+date,teacher); assertThat(fallback.path("scheduled").asBoolean()).isFalse(); assertThat(fallback.path("students").size()).isEqualTo(4);
        post("/schedules",boss,Map.of("classId",1,"courseId",1,"date",date,"startTime","16:00","endTime","18:00","units",1));
        post("/schedules",boss,Map.of("classId",1,"courseId",1,"date",date,"startTime","18:00","endTime","19:00","units",1));
        assertThat(get("/workbench?classId=1&date="+date,teacher).path("students").size()).isEqualTo(4);
        db.update("UPDATE course SET enabled=FALSE WHERE id=1");
        try { assertThat(get("/workbench?classId=1&date="+date,teacher).path("scheduled").asBoolean()).isFalse(); }
        finally { db.update("UPDATE course SET enabled=TRUE WHERE id=1"); }
        call(HttpMethod.GET,"/workbench",parent,null,403);
        call(HttpMethod.GET,"/growth?studentId=5",parent,null,403);
        call(HttpMethod.GET,"/growth?studentId=5",teacher,null,403);
        long foreignStudent=db.count("SELECT MAX(s.id) FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE cl.campus_id=2");
        call(HttpMethod.GET,"/growth?studentId="+foreignStudent,principal,null,403);
        assertThat(get("/growth?studentId="+foreignStudent,boss).path("student").path("id").asLong()).isEqualTo(foreignStudent);
        long id=tasks(date,List.of(1L)).get(0).path("id").asLong();
        var review=Map.of("status","COMPLETED","accuracy",100,"comment","并发核对","expectedVersion",0,"requestId",key());
        List<CompletableFuture<JsonNode>> jobs=new ArrayList<>(); for(int i=0;i<3;i++) jobs.add(CompletableFuture.supplyAsync(()->post("/homework/"+id+"/review",teacher,review))); for(var job:jobs) assertThat(job.join().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(db.count("SELECT COUNT(*) FROM growth_event WHERE source_type='HOMEWORK' AND source_id=?",id)).isEqualTo(1);
    }
}
