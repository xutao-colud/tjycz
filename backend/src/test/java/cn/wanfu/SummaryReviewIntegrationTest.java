package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:summary-review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/summary-review-uploads"})
@ActiveProfiles("local")
class SummaryReviewIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String teacher,parent,boss,principal,date;
    static final AtomicInteger day=new AtomicInteger();
    @BeforeEach void login() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        teacher=demo("TEACHER"); parent=demo("PARENT"); boss=demo("ADMIN"); principal=demo("CAMPUS_ADMIN");
        date=sec.today().minusDays(90+day.getAndIncrement()).toString();
    }
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(token!=null) headers.setBearerAuth(token);
        var result=http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
        assertThat(result.getStatusCode().value()).as(method+" "+path+": "+result.getBody()).isEqualTo(status);
        try { return json.readTree(result.getBody()); } catch(Exception error) { throw new AssertionError(error); }
    }
    String demo(String role) { return call(HttpMethod.POST,"/auth/demo",null,Map.of("role",role),200).path("token").asText(); }
    JsonNode get(String path,String token) { return call(HttpMethod.GET,path,token,null,200); }
    JsonNode post(String path,String token,Object body) { return call(HttpMethod.POST,path,token,body,200); }
    String path(JsonNode summary) { return "/summaries/"+summary.path("id").asLong(); }
    Map<String,Object> version(JsonNode summary) { return Map.of("expectedVersion",summary.path("version").asInt()); }
    JsonNode create(String author,long student) {
        post("/summaries/generate",author,Map.of("date",date,"studentIds",List.of(student)));
        for(var item:get("/summaries?date="+date,author)) if(item.path("studentId").asLong()==student) return get(path(item),author);
        throw new AssertionError("新生成小结缺失");
    }
    JsonNode submit(JsonNode summary,String author) { return post(path(summary)+"/submit-review",author,version(summary)); }
    JsonNode review(JsonNode summary,String reviewer,boolean approved,String comment) {
        return post(path(summary)+"/review",reviewer,Map.of("expectedVersion",summary.path("version").asInt(),"approved",approved,"comment",comment));
    }
    List<String> actions(JsonNode history) { List<String> out=new ArrayList<>(); for(var item:history) out.add(item.path("action").asText()); return out; }

    @Test void independentApprovalFreezesContentAndPublishedSnapshot() {
        var draft=create(teacher,1);
        draft=call(HttpMethod.PATCH,path(draft),teacher,Map.of("content","虚构审核正文 A","teacherComment","教师寄语 A","expectedVersion",draft.path("version").asInt()),200);
        var pending=submit(draft,teacher); assertThat(pending.path("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(pending.path("reviewVersion").asInt()).isEqualTo(pending.path("version").asInt());
        assertThat(pending.path("submittedBy").asLong()).isEqualTo(get("/me",teacher).path("id").asLong());
        assertThat(pending.path("submittedAt").isTextual()).isTrue();
        call(HttpMethod.PATCH,path(pending),teacher,Map.of("teacherComment","待审时偷改","expectedVersion",pending.path("version").asInt()),400);
        call(HttpMethod.GET,path(pending),parent,null,403); assertThat(get("/summaries?date="+date,parent).size()).isZero();
        var approved=review(pending,principal,true,"独立核对通过");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("reviewedBy").asLong()).isEqualTo(get("/me",principal).path("id").asLong());
        assertThat(approved.path("reviewedBy")).isNotEqualTo(approved.path("submittedBy"));
        assertThat(approved.path("reviewedAt").isTextual()).isTrue(); assertThat(approved.path("reviewComment").asText()).isEqualTo("独立核对通过");
        post("/fitness/batch",teacher,Map.of("date",date,"studentIds",List.of(1),"project","审核后新增运动","count",10,"duration",60));
        assertThat(post("/summaries/generate",teacher,Map.of("date",date,"studentIds",List.of(1))).path("generatedCount").asInt()).isZero();
        assertThat(get(path(approved),teacher).path("content").asText()).isEqualTo("虚构审核正文 A");
        assertThat(get("/summaries?date="+date,teacher).get(0).path("content").asText()).isEqualTo("虚构审核正文 A");
        call(HttpMethod.PATCH,path(approved),teacher,Map.of("teacherComment","通过后偷改","expectedVersion",approved.path("version").asInt()),400);
        var published=post(path(approved)+"/publish",teacher,Map.of());
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED"); assertThat(published.path("content").asText()).isEqualTo("虚构审核正文 A");
        assertThat(published.path("teacherComment").asText()).isEqualTo("教师寄语 A");
        var repeated=post(path(published)+"/publish",teacher,Map.of());
        assertThat(repeated.path("version")).isEqualTo(published.path("version")); assertThat(repeated.path("publishedAt")).isEqualTo(published.path("publishedAt"));
        assertThat(actions(repeated.path("reviewHistory"))).containsExactly("GENERATE","EDIT","SUBMIT","APPROVE","PUBLISH");
        var visible=get(path(published),parent); assertThat(visible.path("content").asText()).isEqualTo("虚构审核正文 A");
        assertThat(visible.path("reviewHistory").size()).isZero(); assertThat(visible.has("reviewComment")).isFalse();
        post(path(published)+"/feedback",parent,Map.of("satisfaction","SATISFIED","text","家长已阅"));
        assertThat(get(path(published),teacher).path("feedbackSatisfaction").asText()).isEqualTo("SATISFIED");
        assertThat(post("/summaries/generate",teacher,Map.of("date",date,"studentIds",List.of(1))).path("generatedCount").asInt()).isZero();
        call(HttpMethod.PATCH,path(published),teacher,Map.of("teacherComment","发布后覆盖"),400);
    }
    @Test void reviewAndWithdrawalRequireCorrectRoleScopeAndDifferentReviewer() {
        var pending=submit(create(principal,1),principal); String path=path(pending);
        var decision=Map.of("expectedVersion",pending.path("version").asInt(),"approved",true,"comment","核对通过");
        call(HttpMethod.POST,path+"/review",teacher,decision,403); call(HttpMethod.POST,path+"/review",parent,decision,403);
        call(HttpMethod.POST,path+"/review",principal,decision,403);
        call(HttpMethod.POST,path+"/withdraw-review",boss,version(pending),403);
        var approved=review(pending,boss,true,"老板独立核对"); call(HttpMethod.POST,path+"/publish",parent,Map.of(),403);
        assertThat(approved.path("canPublish").asBoolean()).isTrue();
        long foreign=Db.id(db.one("SELECT s.id FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE cl.campus_id=2 ORDER BY s.id"),"id");
        var other=submit(create(boss,foreign),boss);
        call(HttpMethod.POST,path(other)+"/review",principal,Map.of("expectedVersion",other.path("version").asInt(),"approved",true,"comment","跨校区审核"),403);
    }
    @Test void staleVersionsWithdrawalAndRejectionKeepAppendOnlyHistory() {
        var original=create(teacher,1);
        var edited=call(HttpMethod.PATCH,path(original),teacher,Map.of("teacherComment","第一次评语","expectedVersion",original.path("version").asInt()),200);
        call(HttpMethod.POST,path(original)+"/submit-review",teacher,version(original),409);
        var pending=submit(edited,teacher);
        call(HttpMethod.POST,path(pending)+"/review",principal,Map.of("expectedVersion",edited.path("version").asInt(),"approved",true,"comment","过期版本"),409);
        var withdrawn=post(path(pending)+"/withdraw-review",teacher,version(pending)); assertThat(withdrawn.path("status").asText()).isEqualTo("DRAFT");
        call(HttpMethod.POST,path(pending)+"/review",principal,Map.of("expectedVersion",pending.path("version").asInt(),"approved",true,"comment","撤回前的旧请求"),409);
        pending=submit(withdrawn,teacher); var rejected=review(pending,principal,false,"请补充具体作业情况");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED"); assertThat(rejected.path("canEdit").asBoolean()).isTrue();
        call(HttpMethod.POST,path(rejected)+"/publish",teacher,Map.of(),400);
        edited=call(HttpMethod.PATCH,path(rejected),teacher,Map.of("content","补充后的正文 B","teacherComment","补充评语 B","expectedVersion",rejected.path("version").asInt()),200);
        var approved=review(submit(edited,teacher),principal,true,"修改后核对通过");
        var published=post(path(approved)+"/publish",teacher,Map.of()); assertThat(published.path("content").asText()).isEqualTo("补充后的正文 B");
        assertThat(actions(published.path("reviewHistory"))).containsExactly("GENERATE","EDIT","SUBMIT","WITHDRAW","SUBMIT","REJECT","EDIT","SUBMIT","APPROVE","PUBLISH");
        var history=published.path("reviewHistory");
        assertThat(history.get(5).path("comment").asText()).isEqualTo("请补充具体作业情况");
        assertThat(history.get(8).path("comment").asText()).isEqualTo("修改后核对通过");
        assertThat(history.get(5).path("teacherComment").asText()).isEqualTo("第一次评语");
    }
    @Test void bothPublishEntriesRequireApprovalAndConcernConfirmation() {
        var draft=create(teacher,1); long id=draft.path("id").asLong();
        call(HttpMethod.POST,path(draft)+"/publish",teacher,Map.of(),400);
        assertThat(post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id))).path("results").get(0).path("success").asBoolean()).isFalse();
        var pending=submit(draft,teacher);
        assertThat(post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id))).path("results").get(0).path("success").asBoolean()).isFalse();
        var approved=review(pending,principal,true,"核对通过");
        post("/follow-ups",teacher,Map.of("studentId",1,"date",date,"kind","ISSUE","type","LEARNING","title","新增关注事项","reason","需要继续辅导","measures","老师正在指导","needsParent",false,"requestId",UUID.randomUUID().toString()));
        call(HttpMethod.POST,path(approved)+"/publish",teacher,Map.of(),400);
        var result=post("/summaries/batch-publish",teacher,Map.of("summaryIds",List.of(id),"confirmedConcernStudentIds",List.of(1)));
        assertThat(result.path("results").get(0).path("success").asBoolean()).isTrue();
        assertThat(get(path(approved),parent).path("content")).isEqualTo(approved.path("content"));
    }
    @Test void mistakeHistoryPreservesEveryCorrectionDecisionAndReviewCompletion() {
        long id=post("/mistakes",teacher,Map.of("studentId",1,"subject","数学","title","虚构进位练习","knowledgePoint","进位","errorReason","遗漏进位")).path("id").asLong(); String path="/mistakes/"+id;
        post(path+"/correct",parent,Map.of("text","第一次订正过程"));
        call(HttpMethod.POST,path+"/review",teacher,Map.of("approved",false,"comment",""),400);
        post(path+"/review",teacher,Map.of("approved",false,"comment","第一次复核：仍需验算"));
        post(path+"/correct",parent,Map.of("text","第二次订正并完成验算"));
        var approved=post(path+"/review",teacher,Map.of("approved",true,"comment","第二次复核：验算正确"));
        post(path+"/review",teacher,Map.of("approved",true,"comment","重复审核不覆盖"));
        long review=approved.path("reviews").get(0).path("id").asLong(); db.update("UPDATE mistake_review SET due_date=? WHERE id=?",sec.today(),review);
        post("/mistakes/reviews/"+review+"/complete",teacher,Map.of()); post("/mistakes/reviews/"+review+"/complete",teacher,Map.of());
        var detail=get(path,parent); var history=detail.path("history");
        assertThat(actions(history)).containsExactly("CREATE","CORRECT","REJECT","CORRECT","APPROVE","REVIEW_COMPLETE");
        assertThat(history.get(1).path("comment").asText()).isEqualTo("第一次订正过程"); assertThat(history.get(2).path("comment").asText()).isEqualTo("第一次复核：仍需验算");
        assertThat(history.get(3).path("comment").asText()).isEqualTo("第二次订正并完成验算"); assertThat(history.get(4).path("comment").asText()).isEqualTo("第二次复核：验算正确");
        for(var event:history) { assertThat(event.path("actorId").asLong()).isPositive(); assertThat(event.path("actorName").asText()).isNotBlank(); assertThat(event.path("createdAt").isTextual()).isTrue(); }
        assertThat(history.get(1).path("actorRole").asText()).isEqualTo("PARENT"); assertThat(history.get(4).path("actorRole").asText()).isEqualTo("TEACHER");
        assertThat(history.get(1).path("correctionId")).isEqualTo(detail.path("corrections").get(0).path("id"));
        assertThat(history.get(5).path("reviewId").asLong()).isEqualTo(review);
        call(HttpMethod.POST,path+"/review",parent,Map.of("approved",true,"comment","越权审核"),403);
    }
}
