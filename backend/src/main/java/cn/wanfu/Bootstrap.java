package cn.wanfu;

import java.math.BigDecimal;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public class Bootstrap implements ApplicationRunner {
    final Db db; final Security sec; final FinanceService finance; final Environment env; final TransactionTemplate tx;
    public Bootstrap(Db db,Security sec,FinanceService finance,Environment env,PlatformTransactionManager manager) { this.db=db; this.sec=sec; this.finance=finance; this.env=env; this.tx=new TransactionTemplate(manager); }
    @Override public void run(ApplicationArguments args) {
        boolean prod=Arrays.asList(env.getActiveProfiles()).contains("prod");
        if(prod&&sec.local()) throw new IllegalStateException("prod不可同时启用local");
        if(!prod&&!sec.local()) throw new IllegalStateException("必须明确使用local或prod profile");
        if(prod) {
            String url=env.getProperty("DB_URL",""),username=env.getProperty("DB_USERNAME",""),dbPassword=env.getProperty("DB_PASSWORD",""),password=env.getProperty("ADMIN_PASSWORD",""),admin=env.getProperty("ADMIN_USERNAME","");
            if(!url.startsWith("jdbc:mysql:")||username.isBlank()||dbPassword.isBlank()||admin.isBlank()) throw new IllegalStateException("prod必须配置MySQL DB_URL、DB_USERNAME、DB_PASSWORD、ADMIN_USERNAME、ADMIN_PASSWORD");
            try { ManagementService.validatePassword(password); } catch(ApiException e) { throw new IllegalStateException("ADMIN_PASSWORD须12至72位并含大小写、数字、符号"); }
            String uploads=env.getProperty("APP_UPLOAD_DIR",""); if(uploads.isBlank()||!Path.of(uploads).isAbsolute()) throw new IllegalStateException("prod必须配置绝对路径的私有APP_UPLOAD_DIR");
            try { Files.createDirectories(Path.of(uploads)); if(!Files.isWritable(Path.of(uploads))) throw new IllegalStateException("上传目录不可写"); } catch(java.io.IOException e) { throw new IllegalStateException("无法创建私有上传目录",e); }
            tx.executeWithoutResult(s->{
                if(db.count("SELECT COUNT(*) FROM app_user WHERE role='SUPER_ADMIN'")==0) db.update("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'SUPER_ADMIN')",admin,sec.passwords.encode(password),"总管理员");
            });
        } else { tx.executeWithoutResult(s->{ seedBase(); seedCampuses(); }); seedDay(); }
    }
    void seedBase() {
        if(db.count("SELECT COUNT(*) FROM app_user")>0) return;
        long admin=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'SUPER_ADMIN')","admin",sec.passwords.encode("Admin123!"),"演示总管理员");
        long teacher=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'TEACHER')","teacher",sec.passwords.encode("Teacher123!"),"林老师");
        long parent=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'PARENT')","parent",sec.passwords.encode("Parent123!"),"陈家长");
        long class1=db.insert("INSERT INTO school_class(name,teacher_name) VALUES('向阳一班','林老师')"),class2=db.insert("INSERT INTO school_class(name,teacher_name) VALUES('星光二班','周老师')");
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,class1);
        long course=db.insert("INSERT INTO course(name,type,unit,price,warning_threshold,enabled) VALUES('晚辅托管','EVENING','次',8000,3,TRUE)");
        db.update("INSERT INTO course(name,type,unit,price,warning_threshold,enabled) VALUES('思维拓展','WEEKEND','节',12000,2,TRUE)");
        String[] names={"陈一诺","陈子涵","王小满","李沐阳","张可欣","刘星辰","赵安然","周乐乐"}; int[] balances={12,3,10,8,2,15,6,1};
        for(int i=0;i<8;i++) {
            long sid=db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES(?,?,?,?,?,?)",names[i],i%2==0?"女":"男",i<4?"三年级":"四年级",i<4?class1:class2,i<2?"陈家长":names[i].substring(0,1)+"家长","1380000000"+i);
            if(i<2) db.update("INSERT INTO parent_student VALUES(?,?)",parent,sid);
            long account=db.insert("INSERT INTO lesson_account(student_id,course_id) VALUES(?,?)",sid,course);
            db.update("INSERT INTO enrollment(student_id,course_id,account_id) VALUES(?,?,?)",sid,course,account);
            finance.ledger(finance.account(account),"OPENING",BigDecimal.valueOf(balances[i]),BigDecimal.ZERO,BigDecimal.ZERO,"演示初始购买","demo:opening:"+sid,null);
        }
        db.update("INSERT INTO audit_log(actor_name,action,detail,created_at) VALUES('系统','DEMO_INIT','本地8名虚构学生、2班初始化',?)",sec.now());
    }
    void seedCampuses() {
        // Additional demo data is additive, including upgrades of existing local databases.
        if(db.count("SELECT COUNT(*) FROM app_user WHERE username='campus_admin'")==0) {
            long principal=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'ADMIN')","campus_admin",sec.passwords.encode("CampusAdmin123!"),"主校区校长");
            db.update("INSERT INTO user_campus VALUES(?,1)",principal);
        }
        if(db.count("SELECT COUNT(*) FROM app_user WHERE username='campus_admin_2'")>0) return;
        long campus=db.insert("INSERT INTO campus(name,address) VALUES('东校区','演示东校区')");
        long principal=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'ADMIN')","campus_admin_2",sec.passwords.encode("CampusAdmin123!"),"东校区校长");
        db.update("INSERT INTO user_campus VALUES(?,?)",principal,campus);
        long teacher=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'TEACHER')","teacher_2",sec.passwords.encode("Teacher123!"),"东校区老师");
        long parent=db.insert("INSERT INTO app_user(username,password_hash,name,role) VALUES(?,?,?,'PARENT')","parent_2",sec.passwords.encode("Parent123!"),"东校区家长");
        long cl=db.insert("INSERT INTO school_class(name,teacher_name,campus_id) VALUES('东校区一班','东校区老师',?)",campus);
        db.update("INSERT INTO teacher_class VALUES(?,?)",teacher,cl);
        long course=db.insert("INSERT INTO course(name,type,unit,price,warning_threshold,enabled,campus_id) VALUES('东校区晚辅','EVENING','次',8000,3,TRUE,?)",campus);
        long student=db.insert("INSERT INTO student(name,gender,grade,class_id,parent_name,parent_phone) VALUES('东校区学生','女','三年级',?,'东校区家长','13900000000')",cl);
        db.update("INSERT INTO parent_student VALUES(?,?)",parent,student);
        long account=db.insert("INSERT INTO lesson_account(student_id,course_id) VALUES(?,?)",student,course);
        db.update("INSERT INTO enrollment(student_id,course_id,account_id) VALUES(?,?,?)",student,course,account);
        finance.ledger(finance.account(account),"OPENING",BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,"演示初始购买","demo:opening:"+student,null);
    }
    @org.springframework.scheduling.annotation.Scheduled(cron="0 0 0 * * *",zone="Asia/Shanghai")
    public synchronized void seedDay() {
        if(!sec.local()) return;
        tx.executeWithoutResult(status->{
            LocalDate date=sec.today(); if(db.count("SELECT COUNT(*) FROM demo_day WHERE date=?",date)>0) return;
            db.update("INSERT INTO demo_day(date) VALUES(?)",date);
            for(long cl:new long[]{1,2}) db.update("INSERT INTO schedule(course_id,class_id,date,start_time,end_time,units,enabled) VALUES(1,?,?,'16:00','19:00',1,TRUE)",cl,date);
            List<String> subjects=List.of("语文","数学","英语");
            for(int sub=0;sub<3;sub++) {
                long task=db.insert("INSERT INTO homework_task(date,subject,type,title,description) VALUES(?,?,?,?,?)",date,subjects.get(sub),sub==2?"ORAL":"WRITTEN",subjects.get(sub)+(sub==2?"朗读练习":"巩固练习"),"完成当日学习内容，认真检查并提交。");
                for(long sid=1;sid<=8;sid++) {
                    String state=switch((int)(sid+sub)%5) { case 0->"COMPLETED"; case 1->"IN_PROGRESS"; case 2->"PENDING_REVIEW"; case 3->"CORRECTION"; default->"NOT_STARTED"; };
                    long h=db.insert("INSERT INTO homework(task_id,student_id,status,accuracy,comment) VALUES(?,?,?,?,?)",task,sid,state,state.equals("COMPLETED")?95:null,state.equals("CORRECTION")?"请检查计算步骤":"");
                    if(state.equals("COMPLETED")) finance.points(sid,finance.rule("HOMEWORK_COMPLETED"),"HOMEWORK_COMPLETED","完成作业","homework:"+h);
                }
            }
            for(long sid:new long[]{1,2,3,5,6}) {
                boolean left=sid==3||sid==6;
                db.update("INSERT INTO attendance(student_id,date,status,check_in_time,check_out_time,source) VALUES(?,?,?,?,?,'DEMO')",sid,date,left?"LEFT":"PRESENT",date.atTime(16,0),left?date.atTime(18,30):null);
                if(left) {
                    var e=db.one("SELECT account_id FROM enrollment WHERE student_id=? AND course_id=1",sid); long sc=Db.id(db.one("SELECT id FROM schedule WHERE class_id=? AND course_id=1 AND date=?",sid<5?1:2,date),"id");
                    long settlement=db.insert("INSERT INTO settlement(student_id,schedule_id,account_id,date,status,reason) VALUES(?,?,?,?,'PENDING','待结算')",sid,sc,e.get("accountId"),date); finance.settle(settlement);
                }
            }
            long fitness=db.insert("INSERT INTO fitness(student_id,date,project,count,duration,confirmed) VALUES(1,?,'跳绳',80,60,TRUE)",date);
            finance.points(1,finance.rule("FITNESS"),"FITNESS","体能：跳绳","fitness:"+fitness);
            long mistake=db.insert("INSERT INTO mistake(student_id,subject,title,knowledge_point,error_reason,status,homework_id,created_at) VALUES(1,'数学','两位数乘法进位','乘法进位','计算时遗漏进位','UNCORRECTED',NULL,?)",sec.now());
            db.update("INSERT INTO daily_summary(student_id,date,status,content,teacher_comment) VALUES(1,?,'DRAFT','今日学习记录待教师审核','继续保持认真检查的好习惯')",date);
            db.update("INSERT INTO daily_summary(student_id,date,status,content,teacher_comment,version,published_at,published_content,published_comment,notification_status) VALUES(2,?,'PUBLISHED','当日已到校，作业学习进行中。','今天学习态度认真',1,?,'当日已到校，作业学习进行中。','今天学习态度认真','NOT_CONFIGURED')",date,sec.now());
        });
    }
}
