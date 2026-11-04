package cn.wanfu;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.privacy.consent-required=true","spring.profiles.active=local","spring.datasource.url=jdbc:h2:mem:family;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","app.upload-dir=./target/family-uploads"})
class FamilyIntegrationTest {
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Db db;@Autowired Security sec;
    String boss,parent,teacher,principal,stranger,foreignPrincipal;long campus,otherCampus,child,otherChild,account,secondAccount,parentId;
    JsonNode call(HttpMethod method,String path,String token,Long context,Object body,int status){
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);if(context!=null)h.set("X-Campus-Id",context.toString());
        var response=http.exchange("/api"+path,method,new HttpEntity<>(body,h),String.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path+" "+response.getBody()).isEqualTo(status);
        try{return json.readTree(response.getBody());}catch(Exception e){throw new AssertionError(e);}
    }
    JsonNode get(String path,String token){return call(HttpMethod.GET,path,token,null,null,200);}
    JsonNode put(String path,String token,Object body){return call(HttpMethod.PUT,path,token,null,body,200);}
    long user(String role){return db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"unused-fixture-hash","虚构家校验收"+role,role);}
    String token(long id){return sec.session(db.one("SELECT id,name,role FROM app_user WHERE id=?",id),false).get("token").toString();}
    long student(long classroom){return db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('虚构孩子','男','三年级',?,'虚构家长','13800000000')",classroom);}
    long course(long campus){return db.insert("INSERT INTO course(name,type,unit,price,warning_threshold,enabled,campus_id) VALUES('虚构独立课程','EVENING','次',1000,3,TRUE,?)",campus);}
    long image(long owner,long campus,String type){return db.insert("INSERT INTO stored_file(owner_id,name,storage_key,content_type,size,created_at,campus_id) VALUES(?,'虚构图片',?,?,32,?,?)",owner,UUID.randomUUID().toString(),type,sec.now(),campus);}
    String setting(){return "/campuses/"+campus+"/family-services";}
    String intent(){return "/accounts/"+account+"/renewal-intent";}
    String meal(){return "/students/"+child+"/meal-order";}
    String page(){return "/family-services?studentId="+child;}
    @BeforeEach void setup(){
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        boss=call(HttpMethod.POST,"/auth/demo",null,null,Map.of("role","ADMIN"),200).path("token").asText();
        campus=db.insert("INSERT INTO campus(name) VALUES('虚构家校校区')");otherCampus=db.insert("INSERT INTO campus(name) VALUES('虚构其他校区')");
        long own=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构本班','虚构老师',?)",campus),other=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('虚构同校其他班','其他老师',?)",campus);
        child=student(own);otherChild=student(other);parentId=user("PARENT");long tid=user("TEACHER"),aid=user("ADMIN"),pid=user("PARENT"),fid=user("ADMIN");
        db.update("INSERT INTO parent_student VALUES(?,?)",parentId,child);db.update("INSERT INTO parent_student VALUES(?,?)",pid,otherChild);db.update("INSERT INTO teacher_class VALUES(?,?)",tid,own);db.update("INSERT INTO user_campus VALUES(?,?)",aid,campus);db.update("INSERT INTO user_campus VALUES(?,?)",fid,otherCampus);
        parent=token(parentId);teacher=token(tid);principal=token(aid);stranger=token(pid);foreignPrincipal=token(fid);
        account=db.insert("INSERT INTO lesson_account(student_id,course_id,balance,purchased) VALUES(?,?,2,2)",child,course(campus));secondAccount=db.insert("INSERT INTO lesson_account(student_id,course_id,balance,purchased) VALUES(?,?,8,8)",child,course(campus));
    }
    @Test void balancesAndParentIntentAreCourseSpecificScopedAndVersioned(){
        var first=get(page(),parent);assertThat(first.path("accounts").get(0).path("lowBalance").asBoolean()).isTrue();assertThat(first.path("accounts").get(1).path("lowBalance").asBoolean()).isFalse();
        var body=Map.of("intention","INTERESTED","expectedVersion",0);var saved=put(intent(),parent,body);assertThat(saved.path("parentId").asLong()).isEqualTo(parentId);
        assertThat(put(intent(),parent,body).path("id")).isEqualTo(saved.path("id"));assertThat(db.count("SELECT COUNT(*) FROM renewal_intent WHERE account_id=?",account)).isEqualTo(1);
        assertThat(get("/renewal-intents?studentId="+child,teacher).get(0).path("intention").asText()).isEqualTo("INTERESTED");assertThat(get("/renewal-intents?studentId="+child,principal).size()).isEqualTo(1);
        call(HttpMethod.GET,page(),stranger,null,null,403);call(HttpMethod.GET,page(),foreignPrincipal,null,null,403);call(HttpMethod.GET,page(),boss,otherCampus,null,403);
        call(HttpMethod.PUT,intent(),teacher,null,body,403);call(HttpMethod.PUT,intent(),stranger,null,body,403);
        call(HttpMethod.PUT,intent(),parent,null,Map.of("intention","NOT_NOW","expectedVersion",0),409);put(intent(),parent,Map.of("intention","NOT_NOW","expectedVersion",1));
        assertThat(get(page(),parent).path("accounts").get(1).path("intention").isNull()).isTrue();
    }
    @Test void unboundParentsAndUnrelatedClassesCannotReadIntents(){
        put(intent(),parent,Map.of("intention","INTERESTED","expectedVersion",0));
        long other=user("TEACHER");db.update("INSERT INTO teacher_class VALUES(?,?)",other,db.one("SELECT class_id FROM student WHERE id=?",otherChild).get("classId"));
        call(HttpMethod.GET,"/renewal-intents?studentId="+child,token(other),null,null,403);
        db.update("DELETE FROM parent_student WHERE user_id=?",parentId);assertThat(get("/renewal-intents",principal).isEmpty()).isTrue();call(HttpMethod.GET,page(),parent,null,null,403);
    }
    @Test void mealOrdersUseServerDatePriceSnapshotAndNoDuplicateOrders(){
        call(HttpMethod.PUT,meal(),parent,null,Map.of("ordered",true,"expectedVersion",0),400);
        put(setting(),principal,Map.of("mealPrice",1800,"expectedVersion",0));var body=Map.of("ordered",true,"expectedVersion",0);var first=put(meal(),parent,body);
        assertThat(first.path("date").asText()).isEqualTo(sec.today().toString());assertThat(first.path("amount").asLong()).isEqualTo(1800);assertThat(first.path("ordered").asBoolean()).isTrue();
        assertThat(put(meal(),parent,body).path("id")).isEqualTo(first.path("id"));assertThat(db.count("SELECT COUNT(*) FROM meal_order WHERE student_id=?",child)).isEqualTo(1);
        put(setting(),principal,Map.of("mealPrice",2000,"expectedVersion",1));assertThat(get(page(),parent).path("meal").path("amount").asLong()).isEqualTo(1800);
        assertThat(get("/meal-orders",teacher).findValues("studentId")).extracting(JsonNode::asLong).contains(child);
        call(HttpMethod.PUT,meal(),teacher,null,body,403);call(HttpMethod.PUT,meal(),stranger,null,body,403);
        call(HttpMethod.PUT,meal(),parent,null,Map.of("ordered",false,"expectedVersion",0),409);put(meal(),parent,Map.of("ordered",false,"expectedVersion",1));
        call(HttpMethod.PUT,meal(),parent,null,Map.of("ordered",true,"amount",1,"expectedVersion",2),400);call(HttpMethod.PUT,meal(),parent,null,Map.of("ordered",true,"date",sec.today().plusDays(1).toString(),"expectedVersion",2),400);
    }
    @Test void paymentCodeIsCampusScopedAndCannotExposeStudentMedia(){
        long owner=user("ADMIN");db.update("INSERT INTO user_campus VALUES(?,?)",owner,campus);String uploader=token(owner);long qr=image(owner,campus,"image/png");
        put(setting(),uploader,Map.of("mealPrice",1800,"paymentQrFileId",qr,"expectedVersion",0));
        assertThat(get(setting(),parent).path("paymentQr").path("id").asLong()).isEqualTo(qr);assertThat(get("/files/"+qr+"/access",parent).path("id").asLong()).isEqualTo(qr);
        call(HttpMethod.GET,setting(),foreignPrincipal,null,null,403);call(HttpMethod.GET,"/files/"+qr+"/access",foreignPrincipal,null,null,403);call(HttpMethod.PUT,setting(),teacher,null,Map.of("mealPrice",1,"expectedVersion",1),403);
        call(HttpMethod.PUT,setting(),uploader,null,Map.of("mealPrice",-1,"expectedVersion",1),400);call(HttpMethod.PUT,setting(),uploader,null,Map.of("mealPrice",1.1,"expectedVersion",1),400);
        long privatePhoto=image(parentId,campus,"image/png");db.update("INSERT INTO file_link(file_id,kind,target_id,student_id) VALUES(?,'HOMEWORK',1,?)",privatePhoto,child);
        call(HttpMethod.PUT,setting(),parent,null,Map.of("paymentQrFileId",privatePhoto,"expectedVersion",1),403);
        call(HttpMethod.PUT,setting(),uploader,null,Map.of("paymentQrFileId",privatePhoto,"expectedVersion",1),403);
    }
    @Test void avatarsRequireImageOwnershipCampusAndMediaConsent(){
        long photo=image(parentId,campus,"image/png");String path="/students/"+child+"/avatar";
        call(HttpMethod.PUT,path,parent,null,Map.of("avatarFileId",photo),403);
        for(String scope:List.of("SERVICE","MEDIA"))call(HttpMethod.POST,"/privacy/consents",parent,null,Map.of("studentId",child,"scope",scope,"policyVersion",PrivacyService.POLICY_VERSION,"granted",true),200);
        assertThat(put(path,parent,Map.of("avatarFileId",photo)).path("avatar").path("id").asLong()).isEqualTo(photo);
        assertThat(get("/students/"+child,teacher).path("avatar").path("id").asLong()).isEqualTo(photo);call(HttpMethod.GET,"/files/"+photo+"/access",stranger,null,null,403);
        long audio=image(parentId,campus,"audio/mpeg"),foreign=image(parentId,otherCampus,"image/png");call(HttpMethod.PUT,path,parent,null,Map.of("avatarFileId",audio),400);call(HttpMethod.PUT,path,parent,null,Map.of("avatarFileId",foreign),403);
        call(HttpMethod.POST,"/privacy/consents",parent,null,Map.of("studentId",child,"scope","MEDIA","policyVersion",PrivacyService.POLICY_VERSION,"granted",false),200);assertThat(get("/students/"+child,teacher).path("avatar").isNull()).isTrue();
    }
    @Test void dissatisfactionRequiresReasonAndNumericRatingsAreRejected(){
        long summary=db.insert("INSERT INTO daily_summary(student_id,date,status,content,published_content,published_comment) VALUES(?,?,'PUBLISHED','虚构小结','虚构小结','加油')",child,sec.today());String path="/summaries/"+summary+"/feedback";
        call(HttpMethod.POST,path,parent,null,Map.of("satisfaction","DISSATISFIED","text","  "),400);call(HttpMethod.POST,path,parent,null,Map.of("rating",5,"text","旧评分"),400);
        var response=call(HttpMethod.POST,path,parent,null,Map.of("satisfaction","DISSATISFIED","text","希望补充作业进展"),200);assertThat(response.path("feedbackSatisfaction").asText()).isEqualTo("DISSATISFIED");assertThat(response.path("feedbackRating").isNull()).isTrue();
        assertThat(get("/summaries/"+summary,teacher).path("feedbackText").asText()).isEqualTo("希望补充作业进展");call(HttpMethod.POST,path,teacher,null,Map.of("satisfaction","SATISFIED"),403);call(HttpMethod.POST,path,stranger,null,Map.of("satisfaction","SATISFIED"),403);
        call(HttpMethod.POST,path,parent,null,Map.of("satisfaction","SATISFIED","text",""),200);
    }
    @Test void availableBalanceThresholdIncludesRefundReservationsAndExactBoundary(){
        db.update("UPDATE lesson_account SET balance=3,purchased=3 WHERE id=?",secondAccount);
        assertThat(get(page(),parent).path("accounts").get(1).path("lowBalance").asBoolean()).isTrue();
        long renewal=db.insert("INSERT INTO renewal(account_id,amount,lessons,gift_lessons,payment_method,status,note,request_id,created_at) VALUES(?,8000,8,0,'CASH','CONFIRMED','虚构余额验收',?,?)",secondAccount,UUID.randomUUID().toString(),sec.now());
        db.update("UPDATE lesson_account SET balance=8,purchased=8 WHERE id=?",secondAccount);
        db.update("INSERT INTO refund_request(renewal_id,account_id,amount,lessons,gift_lessons,reason,status,created_by,created_at,updated_at) VALUES(?,?,5000,5,0,'虚构退款预留','APPROVED',?,?,?)",renewal,secondAccount,parentId,sec.now(),sec.now());
        var available=get(page(),parent).path("accounts").get(1);assertThat(available.path("availableBalance").decimalValue()).isEqualByComparingTo("3");assertThat(available.path("lowBalance").asBoolean()).isTrue();
    }
    @Test void configuredPaymentCodesCannotBecomeStudentMediaAndStudentMediaCannotBecomePaymentCodes(){
        long admin=user("ADMIN");db.update("INSERT INTO user_campus VALUES(?,?)",admin,campus);String uploader=token(admin);
        for(String scope:List.of("SERVICE","MEDIA"))call(HttpMethod.POST,"/privacy/consents",parent,null,Map.of("studentId",child,"scope",scope,"policyVersion",PrivacyService.POLICY_VERSION,"granted",true),200);
        long qr=image(admin,campus,"image/png");put(setting(),uploader,Map.of("paymentQrFileId",qr,"expectedVersion",0));
        call(HttpMethod.PUT,"/students/"+child+"/avatar",parent,null,Map.of("avatarFileId",qr),400);
        long photo=image(parentId,campus,"image/png");put("/students/"+child+"/avatar",parent,Map.of("avatarFileId",photo));
        call(HttpMethod.PUT,setting(),uploader,null,Map.of("paymentQrFileId",photo,"expectedVersion",1),400);
    }
    @Test void studentArrivalNeedsTeacherConfirmationButNoLocationOrFace(){
        var body=Map.of("studentIds",List.of(child),"date",sec.today().toString());var first=call(HttpMethod.POST,"/attendance/check-in",teacher,null,body,200);assertThat(first.path("results").get(0).path("success").asBoolean()).isTrue();
        call(HttpMethod.POST,"/attendance/check-in",teacher,null,body,200);assertThat(db.count("SELECT COUNT(*) FROM attendance WHERE student_id=?",child)).isEqualTo(1);assertThat(db.one("SELECT source FROM attendance WHERE student_id=?",child).get("source")).isEqualTo("MANUAL");
        call(HttpMethod.POST,"/attendance/check-in",parent,null,body,403);
    }
}
