package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
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
    "spring.datasource.url=jdbc:h2:mem:refunds;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "app.upload-dir=./target/refund-uploads"})
@ActiveProfiles("local")
class RefundIntegrationTest {
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired Db db; @Autowired Security sec;
    String boss,parent,teacher,principal;
    @BeforeEach void login() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=demo("ADMIN"); parent=demo("PARENT"); teacher=demo("TEACHER"); principal=demo("CAMPUS_ADMIN");
    }
    ResponseEntity<String> exchange(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(token!=null) headers.setBearerAuth(token);
        return http.exchange("/api"+path,method,new HttpEntity<>(body,headers),String.class);
    }
    JsonNode call(HttpMethod method,String path,String token,Object body,int status) {
        var response=exchange(method,path,token,body);
        assertThat(response.getStatusCode().value()).as(method+" "+path+": "+response.getBody()).isEqualTo(status);
        try { return json.readTree(response.getBody()); } catch(Exception e) { throw new AssertionError(e); }
    }
    JsonNode post(String path,String token,Object body) { return call(HttpMethod.POST,path,token,body,200); }
    JsonNode get(String path,String token) { return call(HttpMethod.GET,path,token,null,200); }
    String demo(String role) { return post("/auth/demo",null,Map.of("role",role)).path("token").asText(); }
    String key() { return UUID.randomUUID().toString(); }
    record Fixture(long student,long account,long renewal,long course,long schoolClass) {}
    Fixture fixture(long student) {
        var s=get("/students/"+student,boss);
        long course=post("/courses",boss,Map.of("name","退款验收-"+key(),"type","WEEKEND","unit","节","price",1000,"warningThreshold",1,"campusId",s.path("campusId").asLong())).path("id").asLong();
        long account=post("/enrollments",boss,Map.of("studentId",student,"courseId",course)).path("accountId").asLong();
        long renewal=renew(account,10,2,10000);
        return new Fixture(student,account,renewal,course,s.path("classId").asLong());
    }
    long renew(long account,int lessons,int gifts,long amount) {
        long id=post("/renewals",boss,Map.of("accountId",account,"amount",amount,"lessons",lessons,"giftLessons",gifts,"paymentMethod","CASH","note","虚构退款验收","requestId",key())).path("id").asLong();
        post("/renewals/"+id+"/confirm",boss,Map.of()); return id;
    }
    Map<String,Object> request(long renewal,long amount,int lessons,int gifts) {
        return new LinkedHashMap<>(Map.of("renewalId",renewal,"amount",amount,"lessons",lessons,"giftLessons",gifts,"reason","虚构验收：申请退回未使用课时","requestId",key()));
    }
    Map<String,Object> action(JsonNode row) { return new LinkedHashMap<>(Map.of("expectedVersion",row.path("version").asInt(),"note","虚构验收：已核对","requestId",key())); }
    JsonNode approve(JsonNode row) { var body=action(row); body.put("approved",true); return post("/refunds/"+row.path("id").asLong()+"/review",boss,body); }
    Map<String,Object> confirmation(JsonNode row) {
        var body=action(row); body.put("confirmed",true); body.put("paymentMethod","CASH"); body.put("paymentReference","虚构现金签收凭证-"+key()); return body;
    }
    JsonNode account(Fixture f) { for(var a:get("/accounts?studentId="+f.student(),boss)) if(a.path("id").asLong()==f.account()) return a; throw new AssertionError("account missing"); }
    long settlement(Fixture f,int units,String start,String end) {
        String date="2020-04-01";
        long schedule=post("/schedules",boss,Map.of("classId",f.schoolClass(),"courseId",f.course(),"date",date,"startTime",start,"endTime",end,"units",units)).path("id").asLong();
        return db.insert("INSERT INTO settlement(student_id,schedule_id,account_id,date,status,reason) VALUES(?,?,?,?,'PENDING','虚构待结算')",f.student(),schedule,f.account(),date);
    }
    @Test void parentApplicationApprovalAndOfflineConfirmationAreTraceableAndIdempotent() {
        var f=fixture(1); var body=request(f.renewal(),3000,3,1);
        List<CompletableFuture<JsonNode>> creates=new ArrayList<>();
        for(int i=0;i<4;i++) creates.add(CompletableFuture.supplyAsync(()->post("/refunds",parent,body)));
        long id=creates.get(0).join().path("id").asLong(); for(var job:creates) assertThat(job.join().path("id").asLong()).isEqualTo(id);
        var row=get("/refunds/"+id,parent); assertThat(row.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(row.path("canReview").asBoolean()).isFalse(); assertThat(row.path("canCancel").asBoolean()).isTrue();
        var conflict=new LinkedHashMap<>(body); conflict.put("amount",4000); call(HttpMethod.POST,"/refunds",parent,conflict,409);
        row=approve(row);
        assertThat(row.path("status").asText()).isEqualTo("APPROVED");
        assertThat(account(f).path("balance").decimalValue()).isEqualByComparingTo("12");
        assertThat(account(f).path("refundReserved").decimalValue()).isEqualByComparingTo("4");
        assertThat(account(f).path("availableBalance").decimalValue()).isEqualByComparingTo("8");
        var confirm=confirmation(row); List<CompletableFuture<JsonNode>> confirmations=new ArrayList<>();
        for(int i=0;i<6;i++) confirmations.add(CompletableFuture.supplyAsync(()->post("/refunds/"+id+"/confirm",boss,confirm)));
        for(var job:confirmations) assertThat(job.join().path("status").asText()).isEqualTo("CONFIRMED");
        var done=get("/refunds/"+id,parent); assertThat(done.path("logs").size()).isEqualTo(3); assertThat(done.path("ledgerId").asLong()).isPositive();
        assertThat(done.path("renewalId").asLong()).isEqualTo(f.renewal()); assertThat(done.path("amount").asLong()).isEqualTo(3000);
        assertThat(done.path("canCancel").asBoolean()).isFalse(); assertThat(done.path("canConfirm").asBoolean()).isFalse();
        var a=account(f); assertThat(a.path("purchased").decimalValue()).isEqualByComparingTo("7"); assertThat(a.path("gifted").decimalValue()).isEqualByComparingTo("1");
        assertThat(a.path("balance").decimalValue()).isEqualByComparingTo("8"); assertThat(a.path("refundReserved").decimalValue()).isEqualByComparingTo("0");
        BigDecimal total=BigDecimal.ZERO; int refunds=0;
        for(var ledger:get("/lesson-ledger?accountId="+f.account(),boss)) {
            assertThat(ledger.path("balanceBefore").decimalValue().add(ledger.path("amount").decimalValue())).isEqualByComparingTo(ledger.path("balanceAfter").decimalValue());
            total=total.add(ledger.path("amount").decimalValue()); if(ledger.path("type").asText().equals("REFUND")) { refunds++; assertThat(ledger.path("amount").decimalValue()).isEqualByComparingTo("-4"); }
        }
        assertThat(refunds).isEqualTo(1); assertThat(total).isEqualByComparingTo(a.path("balance").decimalValue());
        call(HttpMethod.POST,"/refunds/"+id+"/cancel",parent,action(done),400);
        call(HttpMethod.POST,"/refunds/"+id+"/confirm",boss,confirmation(done),400);
        call(HttpMethod.POST,"/lesson-ledger/"+done.path("ledgerId").asLong()+"/reverse",boss,Map.of("reason","不得撤销已退款账目"),400);
        assertThat(db.count("SELECT COUNT(*) FROM audit_log WHERE action='REFUND_CONFIRM' AND detail LIKE ?","refund="+id+",%")).isEqualTo(1);
    }
    @Test void approvalReservesCreditsFromConsumptionAndCancellationReleasesThem() {
        var f=fixture(1); var row=approve(post("/refunds",parent,request(f.renewal(),9000,9,1)));
        assertThat(account(f).path("availableBalance").decimalValue()).isEqualByComparingTo("2");
        long pending=settlement(f,3,"09:00","10:00");
        call(HttpMethod.POST,"/settlements/"+pending+"/retry",boss,Map.of(),400);
        assertThat(account(f).path("consumed").decimalValue()).isEqualByComparingTo("0");
        var cancelled=post("/refunds/"+row.path("id").asLong()+"/cancel",parent,action(row));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(account(f).path("refundReserved").decimalValue()).isEqualByComparingTo("0");
        post("/settlements/"+pending+"/retry",boss,Map.of());
        assertThat(account(f).path("balance").decimalValue()).isEqualByComparingTo("9");
    }
    @Test void consumptionCanUseUnreservedCreditsWhileConfirmedRefundUsesOnlyItsReservation() {
        var f=fixture(1); var row=approve(post("/refunds",parent,request(f.renewal(),9000,9,1)));
        long pending=settlement(f,2,"11:00","12:00"); post("/settlements/"+pending+"/retry",boss,Map.of());
        assertThat(account(f).path("availableBalance").decimalValue()).isEqualByComparingTo("0");
        post("/refunds/"+row.path("id").asLong()+"/confirm",boss,confirmation(row));
        var a=account(f); assertThat(a.path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(a.path("purchased").decimalValue().add(a.path("gifted").decimalValue()).subtract(a.path("consumed").decimalValue())).isEqualByComparingTo("0");
    }
    @Test void originalOrderLimitsIncludePendingRequestsAndConcurrentApplicationsCannotOverclaim() {
        var f=fixture(1); var first=post("/refunds",parent,request(f.renewal(),6000,4,1));
        for(var body:List.of(request(f.renewal(),5000,1,0),request(f.renewal(),0,7,0),request(f.renewal(),0,1,2))) call(HttpMethod.POST,"/refunds",parent,body,400);
        var reject=action(first); reject.put("approved",false);
        assertThat(post("/refunds/"+first.path("id").asLong()+"/review",principal,reject).path("status").asText()).isEqualTo("REJECTED");
        List<CompletableFuture<Integer>> jobs=new ArrayList<>();
        for(int i=0;i<2;i++) { var body=request(f.renewal(),7000,7,0); jobs.add(CompletableFuture.supplyAsync(()->exchange(HttpMethod.POST,"/refunds",parent,body).getStatusCode().value())); }
        assertThat(jobs.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200,400);
        assertThat(account(f).path("refundReserved").decimalValue()).isEqualByComparingTo("0");
        JsonNode source=null; for(var s:get("/refunds/sources?studentId=1",parent)) if(s.path("id").asLong()==f.renewal()) source=s;
        assertThat(source).isNotNull(); assertThat(source.path("refundableAmount").asLong()).isEqualTo(3000);
    }
    @Test void multipleOrdersCannotReserveMoreThanAccountBalanceAndVersionIsRequired() {
        var f=fixture(1); long other=renew(f.account(),10,0,10000);
        long pending=settlement(f,12,"13:00","14:00"); post("/settlements/"+pending+"/retry",boss,Map.of());
        var a=post("/refunds",parent,request(f.renewal(),8000,8,0)); var b=post("/refunds",parent,request(other,8000,8,0));
        var missing=Map.of("approved",true,"note","必须核对版本","requestId",key());
        call(HttpMethod.POST,"/refunds/"+a.path("id").asLong()+"/review",boss,missing,400);
        var approveA=action(a); approveA.put("approved",true); var approveB=action(b); approveB.put("approved",true);
        var one=CompletableFuture.supplyAsync(()->exchange(HttpMethod.POST,"/refunds/"+a.path("id").asLong()+"/review",boss,approveA).getStatusCode().value());
        var two=CompletableFuture.supplyAsync(()->exchange(HttpMethod.POST,"/refunds/"+b.path("id").asLong()+"/review",boss,approveB).getStatusCode().value());
        assertThat(List.of(one.join(),two.join())).containsExactlyInAnyOrder(200,400);
        assertThat(account(f).path("refundReserved").decimalValue()).isEqualByComparingTo("8");
        JsonNode approved=get("/refunds/"+a.path("id").asLong(),boss); if(!approved.path("status").asText().equals("APPROVED")) approved=get("/refunds/"+b.path("id").asLong(),boss);
        var stale=action(approved); stale.put("expectedVersion",0); call(HttpMethod.POST,"/refunds/"+approved.path("id").asLong()+"/cancel",boss,stale,409);
        var unsafe=confirmation(approved); unsafe.remove("confirmed"); call(HttpMethod.POST,"/refunds/"+approved.path("id").asLong()+"/confirm",boss,unsafe,400);
    }
    @Test void refundPermissionsAndOriginalLedgerProtectionCoverReadsAndMutations() {
        var f=fixture(1); var row=post("/refunds",parent,request(f.renewal(),1000,1,0)); long id=row.path("id").asLong();
        call(HttpMethod.GET,"/refunds",null,null,401); call(HttpMethod.GET,"/refunds",teacher,null,403);
        call(HttpMethod.POST,"/refunds",teacher,request(f.renewal(),1000,1,0),403);
        var review=action(row); review.put("approved",true); call(HttpMethod.POST,"/refunds/"+id+"/review",parent,review,403);
        call(HttpMethod.POST,"/refunds/"+id+"/confirm",parent,confirmation(row),403);
        long sourceLedger=0; for(var l:get("/lesson-ledger?accountId="+f.account(),boss)) if(l.path("type").asText().equals("RENEWAL")) sourceLedger=l.path("id").asLong();
        call(HttpMethod.POST,"/lesson-ledger/"+sourceLedger+"/reverse",boss,Map.of("reason","不可绕过退款"),400);
        long foreign=db.count("SELECT MAX(s.id) FROM student s JOIN school_class cl ON cl.id=s.class_id WHERE cl.campus_id=2");
        var foreignFixture=fixture(foreign); var foreignRow=post("/refunds",boss,request(foreignFixture.renewal(),1000,1,0)); long foreignId=foreignRow.path("id").asLong();
        for(String token:List.of(parent,principal)) {
            call(HttpMethod.GET,"/refunds/"+foreignId,token,null,403);
            call(HttpMethod.GET,"/refunds?studentId="+foreign,token,null,403);
            call(HttpMethod.GET,"/refunds/sources?studentId="+foreign,token,null,403);
            call(HttpMethod.POST,"/refunds",token,request(foreignFixture.renewal(),1000,1,0),403);
        }
        call(HttpMethod.POST,"/refunds/"+foreignId+"/cancel",parent,action(foreignRow),403);
        call(HttpMethod.POST,"/refunds/"+foreignId+"/review",principal,review,403);
        assertThat(get("/refunds",parent).findValuesAsText("id")).doesNotContain(Long.toString(foreignId));
    }
    @Test void invalidAmountsAndUnconfirmedOrReversedOrdersCannotBeRefunded() {
        var f=fixture(1);
        var decimal=request(f.renewal(),1000,1,0); decimal.put("amount",1000.5); call(HttpMethod.POST,"/refunds",parent,decimal,400);
        var precision=request(f.renewal(),1000,1,0); precision.put("lessons",1.001); call(HttpMethod.POST,"/refunds",parent,precision,400);
        var absent=request(f.renewal(),1000,1,0); absent.put("giftLessons",null); call(HttpMethod.POST,"/refunds",parent,absent,400);
        for(var body:List.of(request(f.renewal(),-1,1,0),request(f.renewal(),1,0,0),request(f.renewal(),10001,1,0))) call(HttpMethod.POST,"/refunds",parent,body,400);
        long pending=post("/renewals",boss,Map.of("accountId",f.account(),"amount",1000,"lessons",1,"giftLessons",0,"paymentMethod","CASH","requestId",key())).path("id").asLong();
        call(HttpMethod.POST,"/refunds",parent,request(pending,1000,1,0),400);
        var application=request(f.renewal(),1000,1,0); var cancelled=post("/refunds",parent,application);
        post("/refunds/"+cancelled.path("id").asLong()+"/cancel",parent,action(cancelled));
        long original=get("/lesson-ledger?accountId="+f.account(),boss).get(0).path("id").asLong(); post("/lesson-ledger/"+original+"/reverse",boss,Map.of("reason","虚构错单冲正"));
        assertThat(post("/refunds",parent,application).path("status").asText()).isEqualTo("CANCELLED");
        call(HttpMethod.POST,"/refunds",parent,request(f.renewal(),1000,1,0),400);
    }
}
