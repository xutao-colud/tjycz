package cn.wanfu;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
    final Db db; final Security sec; final GrowthService growth; final AttendanceSafetyService safety; final AttendanceLocationService locations; final StudentNotificationService notifications;
    static final BigDecimal ZERO=BigDecimal.ZERO;
    public FinanceService(Db db,Security sec,GrowthService growth,AttendanceSafetyService safety,AttendanceLocationService locations,StudentNotificationService notifications) { this.db=db; this.sec=sec; this.growth=growth; this.safety=safety; this.locations=locations; this.notifications=notifications; }
    BigDecimal dec(Map<String,Object> r,String key) { return new BigDecimal(r.get(key).toString()); }
    void lockStudent(long id) { db.one("SELECT id FROM student WHERE id=? FOR UPDATE",id); sec.student(id); }
    Map<String,Object> account(long id) { return db.one("SELECT * FROM lesson_account WHERE id=? FOR UPDATE",id); }
    BigDecimal reserved(long accountId) { return dec(db.one("SELECT COALESCE(SUM(lessons+gift_lessons),0) amount FROM refund_request WHERE account_id=? AND status='APPROVED'",accountId),"amount"); }
    BigDecimal available(Map<String,Object> account) { return dec(account,"balance").subtract(reserved(Db.id(account,"id"))); }
    int rule(String event) { return Integer.parseInt(db.one("SELECT setting_value FROM app_setting WHERE setting_key=?",event).get("settingValue").toString()); }
    void points(long studentId,int amount,String type,String reason,String event) {
        // Bootstrap seeds demo rewards before a request identity exists; callers enforce actor access.
        var student=db.one("SELECT id,privacy_erased_at FROM student WHERE id=? FOR UPDATE",studentId);
        if(student.get("privacyErasedAt")!=null) throw new ApiException(409,"该学生资料已脱敏，不能继续处理");
        if(db.count("SELECT COUNT(*) FROM point_ledger WHERE event_key=?",event)>0) return;
        int before=((Number)db.one("SELECT points FROM student WHERE id=?",studentId).get("points")).intValue();
        if((long)before+amount<0||(long)before+amount>Integer.MAX_VALUE) throw ApiException.bad("积分余额不足或超出范围");
        db.update("UPDATE student SET points=? WHERE id=?",before+amount,studentId);
        db.update("INSERT INTO point_ledger(student_id,amount,balance,type,reason,event_key,created_at) VALUES(?,?,?,?,?,?,?)",studentId,amount,before+amount,type,reason,event,sec.now());
    }
    long ledger(Map<String,Object> a,String type,BigDecimal p,BigDecimal g,BigDecimal c,String reason,String event,Long original) {
        BigDecimal amount=p.add(g).subtract(c), before=dec(a,"balance"), after=before.add(amount);
        if(after.signum()<0||dec(a,"purchased").add(p).signum()<0||dec(a,"gifted").add(g).signum()<0||dec(a,"consumed").add(c).signum()<0) throw ApiException.bad("账户余额或累计课时不足");
        long accountId=Db.id(a,"id");
        if(after.compareTo(reserved(accountId))<0) throw ApiException.bad("可用课时不足，已预留退款课时不可再次消耗或冲正");
        db.update("UPDATE lesson_account SET balance=?,purchased=purchased+?,gifted=gifted+?,consumed=consumed+? WHERE id=?",after,p,g,c,accountId);
        return db.insert("INSERT INTO lesson_ledger(account_id,type,amount,balance_before,balance_after,reason,event_key,created_at,reversed,original_id,purchased_delta,gifted_delta,consumed_delta) VALUES(?,?,?,?,?,?,?,?,FALSE,?,?,?,?)",accountId,type,amount,before,after,reason,event,sec.now(),original,p,g,c);
    }
    String accountQuery() { return "SELECT a.id,a.student_id,s.name student_name,a.course_id,c.name course_name,c.unit,c.campus_id,(SELECT name FROM campus WHERE id=c.campus_id) campus_name,a.balance,a.purchased,a.gifted,a.consumed,c.warning_threshold,COALESCE((SELECT SUM(r.lessons+r.gift_lessons) FROM refund_request r WHERE r.account_id=a.id AND r.status='APPROVED'),0) refund_reserved,a.balance-COALESCE((SELECT SUM(r.lessons+r.gift_lessons) FROM refund_request r WHERE r.account_id=a.id AND r.status='APPROVED'),0) available_balance FROM lesson_account a JOIN student s ON s.id=a.student_id JOIN course c ON c.id=a.course_id"; }
    String ledgerQuery() { return "SELECT l.id,l.account_id,a.student_id,s.name student_name,c.name course_name,l.type,l.amount,l.balance_before,l.balance_after,l.reason,l.created_at,l.reversed,l.original_id FROM lesson_ledger l JOIN lesson_account a ON a.id=l.account_id JOIN student s ON s.id=a.student_id JOIN course c ON c.id=a.course_id"; }
    String renewalQuery() { return "SELECT r.id,a.student_id,s.name student_name,r.account_id,c.name course_name,r.amount,r.lessons,r.gift_lessons,r.payment_method,r.status,r.note,r.created_at FROM renewal r JOIN lesson_account a ON a.id=r.account_id JOIN student s ON s.id=a.student_id JOIN course c ON c.id=a.course_id"; }
    @Transactional public Map<String,Object> enrollment(long student,long course) {
        sec.staff(); sec.student(student); long campus=sec.course(course); lockStudent(student); db.one("SELECT id FROM course WHERE id=? AND enabled=TRUE",course);
        if(sec.studentCampus(student)!=campus) throw ApiException.bad("课程和学生必须属于同一校区");
        var e=db.optional("SELECT id FROM enrollment WHERE student_id=? AND course_id=?",student,course);
        if(e==null) {
            var a=db.optional("SELECT id FROM lesson_account WHERE student_id=? AND course_id=?",student,course);
            long accountId=a==null?db.insert("INSERT INTO lesson_account(student_id,course_id) VALUES(?,?)",student,course):Db.id(a,"id");
            db.update("INSERT INTO enrollment(student_id,course_id,account_id) VALUES(?,?,?)",student,course,accountId);
            sec.audit("ENROLL","student="+student+", course="+course);
        }
        return db.one("SELECT e.id,e.student_id,s.name student_name,e.course_id,c.name course_name,e.account_id FROM enrollment e JOIN student s ON s.id=e.student_id JOIN course c ON c.id=e.course_id WHERE e.student_id=? AND e.course_id=?",student,course);
    }
    @Transactional public Map<String,Object> renewal(Map<String,Object> b) {
        sec.admin(); long accountId=Db.number(b,"accountId"); var a=db.one("SELECT * FROM lesson_account WHERE id=?",accountId); sec.student(Db.id(a,"studentId")); lockStudent(Db.id(a,"studentId"));
        String request=Db.required(b,"requestId",100),method=Db.required(b,"paymentMethod",30); Db.choice(method,"WECHAT_OFFLINE","ALIPAY","CASH","BANK");
        BigDecimal lessons=Db.decimal(b,"lessons"),gift=Db.decimal(b,"giftLessons"); long amount=Db.number(b,"amount");
        if(lessons.signum()<=0||gift.signum()<0||amount<0||Db.text(b,"note").length()>1000) throw ApiException.bad("金额、课时或备注无效");
        var existing=db.optional("SELECT * FROM renewal WHERE request_id=?",request);
        if(existing!=null) {
            if(Db.id(existing,"accountId")!=accountId||Db.id(existing,"amount")!=amount||dec(existing,"lessons").compareTo(lessons)!=0||dec(existing,"giftLessons").compareTo(gift)!=0||!existing.get("paymentMethod").equals(method)||!existing.get("note").equals(Db.text(b,"note"))) throw new ApiException(409,"requestId已用于不同的续费请求");
            return db.one(renewalQuery()+" WHERE r.id=?",existing.get("id"));
        }
        long id=db.insert("INSERT INTO renewal(account_id,amount,lessons,gift_lessons,payment_method,status,note,request_id,created_at) VALUES(?,?,?,?,?,'PENDING',?,?,?)",accountId,amount,lessons,gift,method,Db.text(b,"note"),request,sec.now());
        sec.audit("RENEWAL_CREATE","renewal="+id); return db.one(renewalQuery()+" WHERE r.id=?",id);
    }
    @Transactional public Map<String,Object> confirm(long id) {
        sec.admin(); var initial=db.one("SELECT a.student_id FROM renewal r JOIN lesson_account a ON a.id=r.account_id WHERE r.id=?",id); sec.student(Db.id(initial,"studentId")); lockStudent(Db.id(initial,"studentId"));
        var r=db.one("SELECT * FROM renewal WHERE id=? FOR UPDATE",id);
        if(r.get("status").equals("PENDING")) {
            var a=account(Db.id(r,"accountId")); ledger(a,"RENEWAL",dec(r,"lessons"),dec(r,"giftLessons"),ZERO,"续费确认 #"+id,"renewal:"+id,null);
            db.update("UPDATE renewal SET status='CONFIRMED' WHERE id=?",id); sec.audit("RENEWAL_CONFIRM","renewal="+id);
        }
        return db.one(renewalQuery()+" WHERE r.id=?",id);
    }
    @Transactional public Map<String,Object> reverse(long id,String reason) {
        sec.admin(); if(reason.isBlank()||reason.length()>1000) throw ApiException.bad("请填写冲正原因（最多1000字）");
        var initial=db.one("SELECT a.student_id FROM lesson_ledger l JOIN lesson_account a ON a.id=l.account_id WHERE l.id=?",id); sec.student(Db.id(initial,"studentId")); lockStudent(Db.id(initial,"studentId"));
        var l=db.one("SELECT * FROM lesson_ledger WHERE id=? FOR UPDATE",id);
        String event=l.get("eventKey").toString();
        if(l.get("type").equals("REFUND")) throw ApiException.bad("已确认退款不可直接冲正，请核对退款记录");
        if(event.startsWith("renewal:")&&db.count("SELECT COUNT(*) FROM refund_request WHERE renewal_id=? AND status IN ('REQUESTED','APPROVED','CONFIRMED')",Long.parseLong(event.substring(8)))>0) throw ApiException.bad("原续费单存在退款记录，不可通过冲正绕过退款流程");
        if(l.get("originalId")!=null) throw ApiException.bad("不能对冲正流水再次冲正");
        var existing=db.optional("SELECT id FROM lesson_ledger WHERE original_id=?",id);
        if(existing!=null) return db.one(ledgerQuery()+" WHERE l.id=?",existing.get("id"));
        long reverseId=ledger(account(Db.id(l,"accountId")),"REVERSAL",dec(l,"purchasedDelta").negate(),dec(l,"giftedDelta").negate(),dec(l,"consumedDelta").negate(),reason,"reverse:"+id,id);
        db.update("UPDATE lesson_ledger SET reversed=TRUE WHERE id=?",id);
        if(event.startsWith("settlement:")) db.update("UPDATE settlement SET status='PENDING',reason='消课已冲正，待补扣' WHERE id=?",Long.parseLong(event.split(":")[1]));
        sec.audit("LEDGER_REVERSE","ledger="+id+", reason="+reason); return db.one(ledgerQuery()+" WHERE l.id=?",reverseId);
    }
    void settle(long id) {
        var t=db.one("SELECT t.*,sc.units FROM settlement t JOIN schedule sc ON sc.id=t.schedule_id WHERE t.id=? FOR UPDATE",id);
        if(t.get("status").equals("SETTLED")) return;
        var a=account(Db.id(t,"accountId")); BigDecimal units=dec(t,"units");
        if(available(a).compareTo(units)<0) { db.update("UPDATE settlement SET reason='可用课时不足（含退款预留），待充值或处理退款后补扣' WHERE id=?",id); return; }
        long sequence=db.count("SELECT COUNT(*) FROM lesson_ledger WHERE event_key LIKE ?","settlement:"+id+":%");
        ledger(a,"CONSUMPTION",ZERO,ZERO,units,"排课消课 "+t.get("date"),"settlement:"+id+":"+sequence,null);
        db.update("UPDATE settlement SET status='SETTLED',reason='已结算' WHERE id=?",id);
    }
    @Transactional public Map<String,Object> retry(long id) {
        sec.staff(); var t=db.one("SELECT * FROM settlement WHERE id=?",id); long sid=Db.id(t,"studentId"); sec.student(sid); lockStudent(sid); settle(id);
        if(db.one("SELECT status FROM settlement WHERE id=?",id).get("status").equals("PENDING")) throw ApiException.bad("余额不足，仍待补扣");
        sec.audit("SETTLEMENT_RETRY","settlement="+id); return Map.of("success",true);
    }
    @Transactional public Map<String,Object> attendance(List<Long> ids,java.time.LocalDate date,boolean checkout,String historicalReason,Map<String,Object> location) {
        return recordAttendance(ids,date,checkout,historicalReason,null);
    }
    Map<String,Object> recordAttendance(List<Long> ids,java.time.LocalDate date,boolean checkout,String historicalReason,Long teacherPhoto) {
        sec.staff(); for(long sid:ids) sec.student(sid);
        growth.pastOrToday(date); java.time.LocalDateTime occurred=date.equals(sec.today())?sec.now():null;
        if(teacherPhoto!=null&&!date.equals(sec.today())) throw ApiException.bad("老师接送拍照仅登记当天");
        List<Long> photos=teacherPhoto==null?List.of():List.of(teacherPhoto);
        List<Map<String,Object>> results=new ArrayList<>();
        for(long sid:ids) {
            lockStudent(sid); var a=db.optional("SELECT * FROM attendance WHERE student_id=? AND date=? FOR UPDATE",sid,date);
            if(!checkout) {
                safety.requireCheckIn(sid,date);
                if(a==null) {
                    long id=db.insert("INSERT INTO attendance(student_id,date,status,check_in_time,source) VALUES(?,?,'PRESENT',?,?)",sid,date,occurred,teacherPhoto==null?"MANUAL":"PHOTO");
                    notifications.attendance(sid,id,false,date);
                    growth.event(sid,date,"ATTENDANCE","ATTENDANCE",id,"CHECK_IN","到校签到",teacherPhoto==null?"老师确认到校":"老师接送拍照签到","PRESENT",occurred,"attendance:"+id+":in",null,Map.of(),photos);
                }
                results.add(Map.of("studentId",sid,"success",true,"message",a==null?"签到成功":"已签到，请勿重复签到"));
            } else {
                if(a==null) { results.add(Map.of("studentId",sid,"success",false,"message","尚未签到")); continue; }
                if(a.get("status").equals("LEFT")) { results.add(Map.of("studentId",sid,"success",true,"message","已签退，无重复扣减")); continue; }
                if(teacherPhoto==null) safety.consumeCheckout(sid,date,historicalReason);
                else safety.requirePhotoCheckout(sid,date);
                var schedules=db.list("SELECT sc.id,e.account_id FROM schedule sc JOIN student s ON s.class_id=sc.class_id JOIN enrollment e ON e.student_id=s.id AND e.course_id=sc.course_id JOIN course c ON c.id=sc.course_id WHERE s.id=? AND sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE ORDER BY sc.id",sid,date);
                for(var sc:schedules) {
                    var old=db.optional("SELECT id FROM settlement WHERE student_id=? AND schedule_id=?",sid,sc.get("id"));
                    long settlement=old==null?db.insert("INSERT INTO settlement(student_id,schedule_id,account_id,date,status,reason) VALUES(?,?,?,?,'PENDING','待结算')",sid,sc.get("id"),sc.get("accountId"),date):Db.id(old,"id");
                    settle(settlement);
                }
                db.update("UPDATE attendance SET status='LEFT',check_out_time=?,source=? WHERE id=?",occurred,teacherPhoto==null?a.get("source"):"PHOTO",a.get("id"));
                notifications.attendance(sid,Db.id(a,"id"),true,date);
                growth.event(sid,date,"ATTENDANCE","ATTENDANCE",Db.id(a,"id"),"CHECK_OUT","离校签退",teacherPhoto==null?"人工离校登记":"老师接送拍照签退","LEFT",occurred,"attendance:"+a.get("id")+":out",null,Map.of(),photos);
                long pending=db.count("SELECT COUNT(*) FROM settlement WHERE student_id=? AND date=? AND status='PENDING'",sid,date);
                results.add(Map.of("studentId",sid,"success",true,"message",pending>0?"签退成功，余额不足已保留待补扣":"签退成功"));
            }
        }
        sec.audit(checkout?"CHECK_OUT":"CHECK_IN",date+" "+ids); return Map.of("results",results);
    }
    @Transactional public Map<String,Object> manualPoints(Map<String,Object> b) {
        sec.staff(); long sid=Db.number(b,"studentId"); sec.student(sid); lockStudent(sid);
        int amount=Db.integer(b,"amount",0); if(amount==0||amount < -100||amount>100) throw ApiException.bad("积分调整须为-100至100的非零整数");
        String reason=Db.required(b,"reason",1000),event="manual:"+Db.required(b,"requestId",100);
        var old=db.optional("SELECT * FROM point_ledger WHERE event_key=?",event);
        if(old!=null&&(Db.id(old,"studentId")!=sid||Db.integer(old,"amount",0)!=amount||!old.get("reason").equals(reason))) throw new ApiException(409,"requestId已用于不同积分调整");
        points(sid,amount,"MANUAL",reason,event); sec.audit("POINTS","student="+sid+", amount="+amount);
        return db.one("SELECT p.id,p.student_id,s.name student_name,p.amount,p.balance,p.type,p.reason,p.created_at FROM point_ledger p JOIN student s ON s.id=p.student_id WHERE p.event_key=?",event);
    }
}
