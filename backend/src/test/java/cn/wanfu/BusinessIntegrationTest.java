package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.*;
import org.springframework.core.io.ByteArrayResource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true",
    "spring.datasource.url=jdbc:h2:mem:business;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/test-uploads"})
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired Db db;
    @Autowired Security sec;
    String admin,teacher,parent;
    @BeforeEach void login() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()); admin=demo("ADMIN"); teacher=demo("TEACHER"); parent=demo("PARENT");
        for(var student:get("/me",parent).path("studentIds")) grantMedia(parent,student.asLong());
    }
    String demo(String role) { return call(HttpMethod.POST,"/auth/demo",null,Map.of("role",role),200).path("token").asText(); }
    JsonNode call(HttpMethod method,String path,String token,Object body,int expected) {
        if(method==HttpMethod.POST && (path.equals("/auth/login")||path.equals("/users")) && body instanceof Map<?,?> values && values.containsKey("password") && !values.containsKey("challengeId") && !values.containsKey("encryptedPassword")) {
            String purpose=path.equals("/users")?"CREATE_USER":"LOGIN";
            Map<String,Object> challenge=mapper.convertValue(get("/auth/challenge?purpose="+purpose,null),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            Map<String,Object> credentials=new LinkedHashMap<>(); values.forEach((key,value)->credentials.put(key.toString(),value));
            body=PasswordTransportTestClient.encrypt(challenge,purpose,credentials);
        }
        return rawCall(method,path,token,body,expected);
    }
    JsonNode rawCall(HttpMethod method,String path,String token,Object body,int expected) {
        if(method==HttpMethod.POST && expected==200 && (path.equals("/attendance/check-in")||path.equals("/teacher-attendance/check-in"))) body=AttendanceLocationFixture.body(db,sec,body);
        HttpHeaders headers=new HttpHeaders(); if(token!=null) headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        // Keep this suite's original main-campus fixtures; CampusIntegrationTest covers cross-campus access.
        if(token!=null) headers.set("X-Campus-Id","1");
        var r=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(r.getStatusCode().value()).as(method+" "+path+" response="+r.getBody()).isEqualTo(expected);
        try { return r.getBody()==null?mapper.nullNode():mapper.readTree(r.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode get(String path,String token) { return call(HttpMethod.GET,path,token,null,200); }
    JsonNode post(String path,String token,Object body) { return call(HttpMethod.POST,path,token,body,200); }
    void grantMedia(String token,long student) {
        String version=get("/privacy/policy",token).path("version").asText();
        for(String scope:List.of("SERVICE","MEDIA")) post("/privacy/consents",token,Map.of("studentId",student,"scope",scope,"policyVersion",version,"granted",true));
    }
    String unique() { return UUID.randomUUID().toString(); }
    long newStudent() {
        long cl=post("/classes",admin,Map.of("name","测试班"+unique(),"teacherName","测试老师")).path("id").asLong();
        return post("/students",admin,Map.of("name","测试学生","gender","男","grade","三年级","classId",cl,"parentName","测试家长","parentPhone","13800000000")).path("id").asLong();
    }
    long account(long sid) { return get("/accounts?studentId="+sid,admin).get(0).path("id").asLong(); }
    JsonNode renew(long account,int lessons) { return post("/renewals",admin,Map.of("accountId",account,"amount",10000,"lessons",lessons,"giftLessons",1,"paymentMethod","CASH","note","测试续费","requestId",unique())); }
    String date="2020-05-17";
    void schedule(long sid,int units) {
        long cl=get("/students/"+sid,admin).path("classId").asLong();
        post("/schedules",admin,Map.of("classId",cl,"courseId",1,"date",date,"startTime","16:00","endTime","18:00","units",units));
    }
    long task(long sid) {
        long tid=post("/homework/tasks",admin,Map.of("date",date,"subject","数学","type","WRITTEN","title","测试作业","description","计算练习","studentIds",List.of(sid))).path("taskId").asLong();
        for(JsonNode h:get("/homework?date="+date+"&studentId="+sid,admin)) if(h.path("taskId").asLong()==tid) return h.path("id").asLong();
        throw new AssertionError("homework missing");
    }
    void complete(long id) { post("/homework/"+id+"/review",admin,Map.of("status","COMPLETED","accuracy",100,"comment","完成")); }
    void concurrent(int n,java.util.function.IntConsumer run) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(n); CountDownLatch start=new CountDownLatch(1); List<Future<?>> jobs=new ArrayList<>();
        try { for(int i=0;i<n;i++) { int index=i; jobs.add(pool.submit(()->{ try { start.await(); run.accept(index); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } })); } start.countDown(); for(var job:jobs) job.get(30,TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); }
    }
    @Test @Order(-1) void passwordChallengeIsAnonymousAndReturnsAnRsaSpkiPublicKey() throws Exception {
        var response=http.getForEntity("/api/auth/challenge",JsonNode.class);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var challenge=response.getBody();
        assertThat(challenge.path("algorithm").asText()).isEqualTo("RSA-OAEP-256");
        assertThat(Base64.getUrlDecoder().decode(challenge.path("challengeId").asText())).hasSize(32);
        assertThat(java.time.Instant.parse(challenge.path("expiresAt").asText())).isAfter(java.time.Instant.now());
        var key=(java.security.interfaces.RSAPublicKey)java.security.KeyFactory.getInstance("RSA").generatePublic(
            new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(challenge.path("publicKey").asText())));
        assertThat(key.getModulus().bitLength()).isEqualTo(2048);
        get("/auth/challenge?purpose=CREATE_USER",null);
        call(HttpMethod.GET,"/auth/challenge?purpose=OTHER",null,null,400);
        for(String path:List.of("/auth/challenge/extra","/auth/challenge-extra","/auth/challenge/"))
            call(HttpMethod.GET,path,null,null,401);
    }
    @Test @Order(0) void sharedLocalCredentialsSelectExistingDemoUsersAndKeepRealLoginRoles() {
        for(String role:List.of("PARENT","TEACHER","ADMIN")) {
            String existingToken=switch(role) { case "PARENT" -> parent; case "TEACHER" -> teacher; default -> admin; };
            var existing=get("/me",existingToken);
            var logged=post("/auth/login",null,Map.of("username","admin","password","admin","role",role));
            assertThat(logged.path("user").path("id")).isEqualTo(existing.path("id"));
            assertThat(logged.path("user").path("role").asText()).isEqualTo(role.equals("ADMIN")?"SUPER_ADMIN":role);
            assertThat(logged.path("demo").asBoolean()).isTrue();
            assertThat(logged.path("token").asText()).isNotBlank();
            String token=logged.path("token").asText();
            assertThat(get("/me",token)).isEqualTo(existing);
            int expectedStudents=switch(role) { case "PARENT" -> 2; case "TEACHER" -> 4; default -> 8; };
            assertThat(get("/students",token).size()).isEqualTo(expectedStudents);
            call(HttpMethod.GET,"/users",token,null,role.equals("ADMIN")?200:403);
        }
        var credentials=new LinkedHashMap<String,Object>(Map.of("username","admin","password","admin"));
        var expectedAdmin=get("/me",admin);
        for(int variant=0;variant<4;variant++) {
            if(variant==1) credentials.put("role","");
            if(variant==2) credentials.put("role","   ");
            if(variant==3) credentials.put("role",null);
            var logged=post("/auth/login",null,credentials);
            assertThat(logged.path("user").path("id")).isEqualTo(expectedAdmin.path("id"));
            assertThat(logged.path("user").path("role").asText()).isEqualTo("SUPER_ADMIN");
            assertThat(logged.path("demo").asBoolean()).isTrue();
            assertThat(get("/me",logged.path("token").asText())).isEqualTo(expectedAdmin);
        }
        for(Object role:List.of("INVALID","admin","ROLE_THAT_IS_TOO_LONG",1))
            call(HttpMethod.POST,"/auth/login",null,Map.of("username","admin","password","admin","role",role),400);
        call(HttpMethod.POST,"/auth/login",null,Map.of("username","admin","password","wrong","role","PARENT"),401);
        call(HttpMethod.POST,"/auth/login",null,Map.of("username","teacher","password","admin","role","ADMIN"),401);
        var real=post("/auth/login",null,Map.of("username","admin","password","Admin123!","role","PARENT"));
        assertThat(real.path("user").path("id")).isEqualTo(expectedAdmin.path("id"));
        assertThat(real.path("user").path("role").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(real.path("demo").asBoolean()).isFalse();
        String realToken=real.path("token").asText();
        var me=get("/me",realToken);
        assertThat(me.path("role").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(me.path("demo").asBoolean()).isFalse();
        get("/users",realToken);
    }
    @Test @Order(1) void demoStatisticsAndCredentialStorageAreAccurate() {
        var d=get("/dashboard",admin); assertThat(d.path("studentCount").asInt()).isEqualTo(8); assertThat(d.path("homeworkCount").asInt()).isEqualTo(24); assertThat(d.path("presentCount").asInt()).isEqualTo(5);
        assertThat(d.path("todayConsumed").decimalValue()).isEqualByComparingTo("2");
        var students=get("/students",admin); int completed=0; for(var s:students) completed+=s.path("homeworkCompleted").asInt(); assertThat(d.path("completedHomeworkCount").asInt()).isEqualTo(completed);
        assertThat(db.one("SELECT password_hash FROM app_user WHERE username='admin'").get("passwordHash").toString()).startsWith("$2a$").doesNotContain("Admin123!");
        assertThat(db.count("SELECT COUNT(*) FROM app_session WHERE token_hash=?",admin)).isZero();
        var logged=post("/auth/login",null,Map.of("username","admin","password","Admin123!")); assertThat(logged.path("demo").asBoolean()).isFalse();
        call(HttpMethod.GET,"/students",null,null,401);
        assertThat(get("/homework?studentId=1",admin).isArray()).isTrue();
    }
    @Test @Order(2) void parentIsolationAndTeacherClassRestrictionsCoverReadsAndWrites() {
        assertThat(get("/students",parent).size()).isEqualTo(2); assertThat(get("/students",teacher).size()).isEqualTo(4);
        for(String path:List.of("/students/3","/accounts?studentId=3","/homework?studentId=3","/points?studentId=3","/mistakes?studentId=3","/renewals?studentId=3","/summaries?studentId=3","/enrollments?studentId=3")) call(HttpMethod.GET,path,parent,null,403);
        call(HttpMethod.GET,"/students?classId=2",teacher,null,403); call(HttpMethod.GET,"/attendance?classId=2",teacher,null,403);
        call(HttpMethod.POST,"/attendance/check-in",teacher,Map.of("studentIds",List.of(1,5),"date",date),403);
        assertThat(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=1 AND date=?",date)).isZero();
        call(HttpMethod.POST,"/points",teacher,Map.of("studentId",5,"amount",2,"reason","越权","requestId",unique()),403);
        call(HttpMethod.POST,"/homework/tasks",teacher,Map.of("studentIds",List.of(5),"date",date,"subject","数学","type","WRITTEN","title","越权"),403);
        call(HttpMethod.GET,"/audit-logs",parent,null,403);
    }
    @Test @Order(3) void concurrentRenewalConfirmationCreditsExactlyOnceAndRequestConflictsFail() throws Exception {
        long sid=newStudent(),acc=account(sid); var order=renew(acc,10); long id=order.path("id").asLong();
        concurrent(8,i->post("/renewals/"+id+"/confirm",admin,Map.of()));
        var a=get("/accounts?studentId="+sid,admin).get(0); assertThat(a.path("balance").decimalValue()).isEqualByComparingTo("11"); assertThat(a.path("purchased").decimalValue()).isEqualByComparingTo("10"); assertThat(a.path("gifted").decimalValue()).isEqualByComparingTo("1");
        assertThat(get("/lesson-ledger?accountId="+acc,admin).size()).isEqualTo(1);
        String request=unique(); var payload=new LinkedHashMap<String,Object>(Map.of("accountId",acc,"amount",100,"lessons",1,"giftLessons",0,"paymentMethod","CASH","requestId",request));
        long first=post("/renewals",admin,payload).path("id").asLong(); assertThat(post("/renewals",admin,payload).path("id").asLong()).isEqualTo(first);
        payload.put("lessons",2); call(HttpMethod.POST,"/renewals",admin,payload,409);
        payload.put("requestId",unique()); payload.put("lessons",1.001); call(HttpMethod.POST,"/renewals",admin,payload,400);
        payload.put("lessons",1); payload.put("amount",100.5); call(HttpMethod.POST,"/renewals",admin,payload,400);
        assertThatThrownBy(()->db.update("UPDATE lesson_account SET balance=balance+1 WHERE id=?",acc)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test @Order(4) void checkInOutRetryAndReversalKeepLedgerBalanced() throws Exception {
        long sid=newStudent(),acc=account(sid); schedule(sid,2);
        var batch=Map.of("studentIds",List.of(sid),"date",date,"historicalReason","虚构验收：已核对历史纸质离校记录，具体时间未知");
        assertThat(post("/attendance/check-out",admin,batch).path("results").get(0).path("success").asBoolean()).isFalse();
        concurrent(4,i->post("/attendance/check-in",admin,batch));
        concurrent(5,i->post("/attendance/check-out",admin,batch));
        assertThat(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=?",sid)).isEqualTo(1);
        var pending=get("/settlements",admin); long settlement=0; for(var s:pending) if(s.path("studentId").asLong()==sid) settlement=s.path("id").asLong(); assertThat(settlement).isPositive();
        assertThat(get("/lesson-ledger?accountId="+acc,admin).size()).isZero();
        var renewal=renew(acc,4); post("/renewals/"+renewal.path("id").asLong()+"/confirm",admin,Map.of());
        long settlementId=settlement; concurrent(4,i->post("/settlements/"+settlementId+"/retry",admin,Map.of()));
        assertThat(get("/accounts?studentId="+sid,admin).get(0).path("balance").decimalValue()).isEqualByComparingTo("3");
        long consumption=0; for(var l:get("/lesson-ledger?accountId="+acc,admin)) if(l.path("type").asText().equals("CONSUMPTION")) consumption=l.path("id").asLong();
        long ledgerId=consumption; concurrent(4,i->post("/lesson-ledger/"+ledgerId+"/reverse",admin,Map.of("reason","错误扣课")));
        assertThat(get("/accounts?studentId="+sid,admin).get(0).path("balance").decimalValue()).isEqualByComparingTo("5");
        assertThat(db.count("SELECT COUNT(*) FROM lesson_ledger WHERE original_id=?",consumption)).isEqualTo(1);
        post("/settlements/"+settlement+"/retry",admin,Map.of());
        var a=get("/accounts?studentId="+sid,admin).get(0); assertThat(a.path("balance").decimalValue()).isEqualByComparingTo("3"); assertThat(a.path("consumed").decimalValue()).isEqualByComparingTo("2");
        var ledger=get("/lesson-ledger?accountId="+acc,admin); BigDecimal sum=BigDecimal.ZERO; for(var l:ledger) { assertThat(l.path("balanceBefore").decimalValue().add(l.path("amount").decimalValue())).isEqualByComparingTo(l.path("balanceAfter").decimalValue()); sum=sum.add(l.path("amount").decimalValue()); }
        assertThat(sum).isEqualByComparingTo(a.path("balance").decimalValue());
    }
    @Test @Order(5) void noScheduleMeansNoConsumption() {
        long sid=newStudent(),acc=account(sid); var r=renew(acc,2); post("/renewals/"+r.path("id").asLong()+"/confirm",admin,Map.of());
        var batch=Map.of("studentIds",List.of(sid),"date",date,"historicalReason","虚构验收：核对历史离校事实，具体时间未知"); post("/attendance/check-in",admin,batch); post("/attendance/check-out",admin,batch);
        assertThat(get("/accounts?studentId="+sid,admin).get(0).path("balance").decimalValue()).isEqualByComparingTo("3"); assertThat(db.count("SELECT COUNT(*) FROM settlement WHERE student_id=?",sid)).isZero();
    }
    @Test @Order(6) void duplicateHomeworkFitnessMistakeAndManualPointsNeverDoubleAward() throws Exception {
        long sid=newStudent(),h=task(sid); concurrent(5,i->complete(h));
        call(HttpMethod.POST,"/homework/"+h+"/review",admin,Map.of("status","CORRECTION","accuracy",20),400);
        var fit=Map.of("date",date,"studentIds",List.of(sid),"project","跳绳","count",100,"duration",60); concurrent(5,i->post("/fitness/batch",admin,fit));
        long mistake=post("/mistakes",admin,Map.of("studentId",sid,"subject","数学","title","测试错题","knowledgePoint","进位","errorReason","遗漏")).path("id").asLong();
        post("/mistakes/"+mistake+"/correct",admin,Map.of("text","已重新计算")); concurrent(5,i->post("/mistakes/"+mistake+"/review",admin,Map.of("approved",true,"comment","正确")));
        assertThat(db.count("SELECT COUNT(*) FROM mistake_review WHERE mistake_id=?",mistake)).isEqualTo(3);
        var manual=new LinkedHashMap<String,Object>(Map.of("studentId",sid,"amount",3,"reason","表现优秀","requestId",unique())); concurrent(5,i->post("/points",admin,manual));
        manual.put("amount",4); call(HttpMethod.POST,"/points",admin,manual,409);
        call(HttpMethod.POST,"/points",admin,Map.of("studentId",sid,"amount",-100,"reason","不可透支","requestId",unique()),400);
        assertThat(get("/students/"+sid,admin).path("points").asInt()).isEqualTo(12); var ledger=get("/points?studentId="+sid,admin); assertThat(ledger.size()).isEqualTo(4); int sum=0; for(var p:ledger) sum+=p.path("amount").asInt(); assertThat(sum).isEqualTo(12);
    }
    @Test @Order(7) void parentCannotReadDraftAndPublishedSnapshotRemainsStable() {
        long sid=1; post("/summaries/generate",admin,Map.of("date",date,"studentIds",List.of(sid)));
        var draft=get("/summaries?date="+date+"&studentId=1",admin).get(0); long id=draft.path("id").asLong();
        assertThat(get("/summaries?date="+date+"&studentId=1",parent).size()).isZero(); call(HttpMethod.POST,"/summaries/"+id+"/feedback",parent,Map.of("rating",5),403);
        call(HttpMethod.PATCH,"/summaries/"+id,admin,Map.of("content","字".repeat(12001),"expectedVersion",draft.path("version").asInt()),400);
        draft=call(HttpMethod.PATCH,"/summaries/"+id,admin,Map.of("teacherComment","认真学习","expectedVersion",draft.path("version").asInt()),200);
        var submitted=post("/summaries/"+id+"/submit-review",admin,Map.of("expectedVersion",draft.path("version").asInt()));
        post("/summaries/"+id+"/review",demo("CAMPUS_ADMIN"),Map.of("expectedVersion",submitted.path("version").asInt(),"approved",true,"comment","独立核对当天记录与教师寄语，通过发布"));
        var published=post("/summaries/"+id+"/publish",admin,Map.of()); String snapshot=published.path("content").asText(); assertThat(snapshot).contains("积分","课时账户","错题","体能","数学"); assertThat(published.path("notificationStatus").asText()).isEqualTo("NOT_CONFIGURED");
        complete(task(sid)); post("/summaries/generate",admin,Map.of("date",date,"studentIds",List.of(sid)));
        assertThat(get("/summaries?date="+date+"&studentId=1",parent).get(0).path("content").asText()).isEqualTo(snapshot);
        call(HttpMethod.PATCH,"/summaries/"+id,admin,Map.of("teacherComment","不允许修改"),400); post("/summaries/"+id+"/feedback",parent,Map.of("satisfaction","SATISFIED","text","谢谢"));
    }
    Map<String,Object> encrypted(String purpose,Map<String,Object> body) {
        Map<String,Object> challenge=mapper.convertValue(get("/auth/challenge?purpose="+purpose,null),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        return PasswordTransportTestClient.encrypt(challenge,purpose,body);
    }
    @Test @Order(9) void encryptedUserCreationChecksAdminBeforeConsumingAndPreservesPassword() {
        String username="encrypted-"+unique(), password="  Teacher123!  ";
        var body=Map.<String,Object>of("username","  "+username+"  ","password",password,"role","TEACHER","name","加密教师","classIds",List.of(1));
        var ciphertext=encrypted("CREATE_USER",body);
        rawCall(HttpMethod.POST,"/users",null,ciphertext,401);
        rawCall(HttpMethod.POST,"/users",teacher,ciphertext,403);
        rawCall(HttpMethod.POST,"/users",parent,ciphertext,403);
        var created=rawCall(HttpMethod.POST,"/users",admin,ciphertext,200);
        assertThat(created.path("username").asText()).isEqualTo(username);
        assertThat(created.path("role").asText()).isEqualTo("TEACHER");
        assertThat(created.has("passwordHash")).isFalse();
        rawCall(HttpMethod.POST,"/users",admin,ciphertext,400);
        var login=post("/auth/login",null,Map.of("username",username,"password",password,"role","ADMIN"));
        assertThat(login.path("user").path("id")).isEqualTo(created.path("id"));
        assertThat(get("/me",login.path("token").asText()).path("role").asText()).isEqualTo("TEACHER");
        call(HttpMethod.POST,"/auth/login",null,Map.of("username",username,"password",password.trim()),401);
        rawCall(HttpMethod.POST,"/users",admin,encrypted("LOGIN",body),400);
        rawCall(HttpMethod.POST,"/auth/login",null,encrypted("CREATE_USER",Map.of("username",username,"password",password)),400);
        var autoCreated=post("/users",admin,Map.of("username","encrypted-"+unique(),"password","Parent123!AaBb","role","PARENT","name","加密家长","studentIds",List.of(1)));
        assertThat(autoCreated.path("role").asText()).isEqualTo("PARENT");
    }
    @Test @Order(10) void plaintextAndMalformedPasswordRequestsHaveUniformSafeErrors() {
        JsonNode expected=null;
        for(String path:List.of("/auth/login","/users")) {
            String token=path.equals("/users")?admin:null;
            String purpose=path.equals("/users")?"CREATE_USER":"LOGIN";
            var plain=Map.<String,Object>of("username","never-stored-"+unique(),"password","NeverLeak123!","name","测试","role","TEACHER","classIds",List.of(1));
            var rejected=rawCall(HttpMethod.POST,path,token,plain,400);
            if(expected==null) expected=rejected;
            assertThat(rejected).isEqualTo(expected);
            for(Object password:Arrays.asList("NeverLeak123!",null)) {
                var mixed=encrypted(purpose,plain); mixed.put("password",password);
                assertThat(rawCall(HttpMethod.POST,path,token,mixed,400)).isEqualTo(expected);
                mixed.remove("password"); assertThat(rawCall(HttpMethod.POST,path,token,mixed,400)).isEqualTo(expected);
            }
            var malformed=encrypted(purpose,plain); malformed.put("encryptedPassword","malformed");
            assertThat(rawCall(HttpMethod.POST,path,token,malformed,400)).isEqualTo(expected);
            assertThat(rawCall(HttpMethod.POST,path,token,Map.of("username","admin","challengeId","unknown","encryptedPassword","malformed"),400)).isEqualTo(expected);
            assertThat(rawCall(HttpMethod.POST,path,token,Map.of(),400)).isEqualTo(expected);
        }
        assertThat(expected.toString()).doesNotContain("NeverLeak123!");
    }
    @Test @Order(8) void attachmentsRequireOwnerOrLinkedStudentAuthorization() {
        byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aUe8AAAAASUVORK5CYII=");
        HttpHeaders headers=new HttpHeaders(); headers.setBearerAuth(parent); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String,Object> body=new LinkedMultiValueMap<>(); body.add("file",new ByteArrayResource(bytes){ @Override public String getFilename(){ return "proof.png"; }});
        var r=http.exchange("/api/files",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class); assertThat(r.getStatusCode().value()).isEqualTo(200); long file=r.getBody().path("id").asLong();
        call(HttpMethod.GET,"/files/"+file,teacher,null,403);
        long guardian=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,'unused','虚构三号学生监护人','PARENT')",unique());
        db.update("INSERT INTO parent_student VALUES(?,3)",guardian); grantMedia(sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",guardian),false).get("token").toString(),3);
        long otherHomework=task(3); call(HttpMethod.POST,"/homework/"+otherHomework+"/attachments",teacher,Map.of("fileIds",List.of(file)),403);
        long ownHomework=task(1); post("/homework/"+ownHomework+"/attachments",parent,Map.of("fileIds",List.of(file)));
        for(String authorized:List.of(parent,teacher)) {
            var access=get("/files/"+file+"/access",authorized); String url=access.path("url").asText();
            assertThat(url).contains("?ticket=").doesNotContain(authorized).doesNotContain("?token=");
            var downloaded=http.getForEntity(url,byte[].class); assertThat(downloaded.getStatusCode().value()).isEqualTo(200); assertThat(downloaded.getBody()).containsExactly(bytes);
            assertThat(http.getForEntity("/api/files/"+file+"?token="+authorized,byte[].class).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(http.getForEntity("/api/files/"+file+"?ticket=invalid",byte[].class).getStatusCode().value()).isEqualTo(401);
        long teacher2=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'TEACHER')",unique(),"unused","二班教师"); db.update("INSERT INTO teacher_class VALUES(?,2)",teacher2);
        var user=db.one("SELECT * FROM app_user WHERE id=?",teacher2); // Minting via normal login is unnecessary for a resource-access fixture.
        String other=(String)new Security(db,java.time.Clock.system(Application.ZONE),new org.springframework.mock.env.MockEnvironment()).session(user,false).get("token");
        call(HttpMethod.GET,"/files/"+file,other,null,403);
    }
    @Test @Order(11) void eveningWeekendAndHolidayAccountsChargeIndependentlyExactlyOnce() throws Exception {
        long sid=newStudent(),defaultAccount=account(sid),cl=get("/students/"+sid,admin).path("classId").asLong();
        String suffix=unique();
        String[] names={"晚辅验收-"+suffix,"周末验收-"+suffix,"寒假验收-"+suffix,"暑假验收-"+suffix};
        String[] types={"EVENING","WEEKEND","HOLIDAY","HOLIDAY"},units={"次","节","小时","节"};
        int[] purchased={6,8,10,12},gifted={1,2,3,4};
        BigDecimal[] consumption={new BigDecimal("0.50"),BigDecimal.ONE,new BigDecimal("1.25"),new BigDecimal("2.00")};
        long[] courseIds=new long[4],accountIds=new long[4];
        for(int i=0;i<names.length;i++) {
            courseIds[i]=post("/courses",admin,Map.of("name",names[i],"type",types[i],"unit",units[i],"price",10000,"warningThreshold",1)).path("id").asLong();
            var enrollment=Map.of("studentId",sid,"courseId",courseIds[i]);
            accountIds[i]=post("/enrollments",admin,enrollment).path("accountId").asLong();
            assertThat(post("/enrollments",admin,enrollment).path("accountId").asLong()).isEqualTo(accountIds[i]);
            var order=post("/renewals",admin,Map.of("accountId",accountIds[i],"amount",10000L*purchased[i],"lessons",purchased[i],"giftLessons",gifted[i],"paymentMethod","CASH","note",names[i],"requestId",unique()));
            post("/renewals/"+order.path("id").asLong()+"/confirm",admin,Map.of());
            post("/schedules",admin,Map.of("classId",cl,"courseId",courseIds[i],"date",date,"startTime",String.format("%02d:00",9+i),"endTime",String.format("%02d:00",10+i),"units",consumption[i]));
        }
        long unenrolled=post("/courses",admin,Map.of("name","未报名验收-"+suffix,"type","WEEKEND","unit","节","price",10000,"warningThreshold",1)).path("id").asLong();
        post("/schedules",admin,Map.of("classId",cl,"courseId",unenrolled,"date",date,"startTime","14:00","endTime","15:00","units",9));
        var batch=Map.of("studentIds",List.of(sid),"date",date,"historicalReason","虚构验收：核对四门独立课程的历史离校，具体时间未知");
        post("/attendance/check-in",admin,batch);
        concurrent(8,i->{
            var result=post("/attendance/check-out",admin,batch).path("results");
            assertThat(result.size()).isEqualTo(1);
            assertThat(result.get(0).path("success").asBoolean()).isTrue();
        });
        Map<Long,JsonNode> accounts=new HashMap<>();
        for(var a:get("/accounts?studentId="+sid,admin)) accounts.put(a.path("courseId").asLong(),a);
        assertThat(accounts).hasSize(5).doesNotContainKey(unenrolled);
        for(int i=0;i<names.length;i++) {
            var a=accounts.get(courseIds[i]);
            assertThat(a.path("id").asLong()).isEqualTo(accountIds[i]);
            assertThat(a.path("purchased").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(purchased[i]));
            assertThat(a.path("gifted").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(gifted[i]));
            assertThat(a.path("consumed").decimalValue()).isEqualByComparingTo(consumption[i]);
            BigDecimal expected=BigDecimal.valueOf(purchased[i]+gifted[i]).subtract(consumption[i]);
            assertThat(a.path("balance").decimalValue()).isEqualByComparingTo(expected);
            assertThat(a.path("purchased").decimalValue().add(a.path("gifted").decimalValue()).subtract(a.path("consumed").decimalValue())).isEqualByComparingTo(expected);
            var ledger=get("/lesson-ledger?accountId="+accountIds[i],admin);
            assertThat(ledger.size()).isEqualTo(2);
            int charged=0; BigDecimal total=BigDecimal.ZERO;
            for(var entry:ledger) {
                assertThat(entry.path("accountId").asLong()).isEqualTo(accountIds[i]);
                assertThat(entry.path("balanceBefore").decimalValue().add(entry.path("amount").decimalValue())).isEqualByComparingTo(entry.path("balanceAfter").decimalValue());
                total=total.add(entry.path("amount").decimalValue());
                if(entry.path("type").asText().equals("CONSUMPTION")) {
                    charged++;
                    assertThat(entry.path("amount").decimalValue()).isEqualByComparingTo(consumption[i].negate());
                }
            }
            assertThat(charged).isEqualTo(1);
            assertThat(total).isEqualByComparingTo(expected);
        }
        List<String> chargedCourses=new ArrayList<>();
        for(var settlement:get("/settlements",admin)) if(settlement.path("studentId").asLong()==sid) {
            assertThat(settlement.path("status").asText()).isEqualTo("SETTLED");
            chargedCourses.add(settlement.path("courseName").asText());
        }
        assertThat(chargedCourses).containsExactlyInAnyOrder(names);
        assertThat(get("/lesson-ledger?accountId="+defaultAccount,admin).size()).isZero();
    }
}
