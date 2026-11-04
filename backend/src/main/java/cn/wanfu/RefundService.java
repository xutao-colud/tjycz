package cn.wanfu;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    final Db db; final Security sec; final FinanceService finance; final GrowthService growth;
    public RefundService(Db db,Security sec,FinanceService finance,GrowthService growth) { this.db=db; this.sec=sec; this.finance=finance; this.growth=growth; }
    void access() { if(!sec.isAdmin()&&!sec.actor().role().equals("PARENT")) throw ApiException.forbidden(); }
    String query() {
        return "SELECT r.*,s.id student_id,s.name student_name,c.name course_name,c.unit,c.campus_id,cp.name campus_name,u.name applicant_name FROM refund_request r JOIN lesson_account a ON a.id=r.account_id JOIN student s ON s.id=a.student_id JOIN course c ON c.id=a.course_id JOIN campus cp ON cp.id=c.campus_id JOIN app_user u ON u.id=r.created_by";
    }
    Map<String,Object> visible(long id) { access(); var row=db.one(query()+" WHERE r.id=?",id); sec.student(Db.id(row,"studentId")); return row; }
    Map<String,Object> decorate(Map<String,Object> row) {
        String status=row.get("status").toString();
        row.put("canReview",sec.isAdmin()&&status.equals("REQUESTED"));
        row.put("canConfirm",sec.isAdmin()&&status.equals("APPROVED"));
        row.put("canCancel",List.of("REQUESTED","APPROVED").contains(status)&&(sec.isAdmin()||sec.actor().id()==Db.id(row,"createdBy")));
        return row;
    }
    List<Map<String,Object>> list(Long student,String status) {
        access(); if(student!=null) sec.student(student);
        List<Object> args=new ArrayList<>(); String sql=query()+" WHERE "+sec.scope("s");
        if(student!=null) { sql+=" AND s.id=?"; args.add(student); }
        if(status!=null&&!status.isBlank()) { Db.choice(status,"REQUESTED","APPROVED","REJECTED","CANCELLED","CONFIRMED"); sql+=" AND r.status=?"; args.add(status); }
        return db.list(sql+" ORDER BY r.id DESC",args.toArray()).stream().map(this::decorate).toList();
    }
    Map<String,Object> detail(long id) {
        var row=decorate(visible(id));
        row.put("logs",db.list("SELECT id,actor_id,actor_name,action,from_status,to_status,text,created_at FROM refund_log WHERE refund_id=? ORDER BY id",id));
        return row;
    }
    Map<String,Object> source(long id,boolean lock) {
        var row=db.one("SELECT r.*,a.student_id FROM renewal r JOIN lesson_account a ON a.id=r.account_id WHERE r.id=?",id);
        sec.student(Db.id(row,"studentId"));
        if(lock) { finance.lockStudent(Db.id(row,"studentId")); row=db.one("SELECT * FROM renewal WHERE id=? FOR UPDATE",id); }
        return row;
    }
    void refundable(Map<String,Object> row) {
        if(!row.get("status").equals("CONFIRMED")||db.count("SELECT COUNT(*) FROM lesson_ledger WHERE event_key=? AND reversed=FALSE","renewal:"+row.get("id"))!=1) throw ApiException.bad("仅可对尚未冲正的已确认续费单申请退款");
    }
    Map<String,Object> remaining(long renewal,long excluded) {
        return db.one("SELECT COALESCE(SUM(amount),0) amount,COALESCE(SUM(lessons),0) lessons,COALESCE(SUM(gift_lessons),0) gift_lessons FROM refund_request WHERE renewal_id=? AND id<>? AND status IN ('REQUESTED','APPROVED','CONFIRMED')",renewal,excluded);
    }
    void limits(Map<String,Object> renewal,long excluded,long amount,BigDecimal lessons,BigDecimal gifts) {
        var used=remaining(Db.id(renewal,"id"),excluded);
        if(amount<0||lessons.signum()<0||gifts.signum()<0||lessons.add(gifts).signum()<=0) throw ApiException.bad("退款金额不能为负，退回课时须大于0");
        if(BigDecimal.valueOf(amount).add(finance.dec(used,"amount")).compareTo(finance.dec(renewal,"amount"))>0||lessons.add(finance.dec(used,"lessons")).compareTo(finance.dec(renewal,"lessons"))>0||gifts.add(finance.dec(used,"giftLessons")).compareTo(finance.dec(renewal,"giftLessons"))>0) throw ApiException.bad("超过原续费单剩余可退金额或购买/赠送课时；待审核申请也占用原单可退额度");
    }
    List<Map<String,Object>> sources(Long student) {
        access(); if(student!=null) sec.student(student);
        String sql=finance.renewalQuery()+" WHERE r.status='CONFIRMED' AND "+sec.scope("s")+" AND EXISTS(SELECT 1 FROM lesson_ledger l WHERE l.event_key=CONCAT('renewal:',r.id) AND l.reversed=FALSE)";
        var rows=student==null?db.list(sql+" ORDER BY r.id DESC"):db.list(sql+" AND a.student_id=? ORDER BY r.id DESC",student);
        List<Map<String,Object>> out=new ArrayList<>();
        for(var row:rows) {
            var used=remaining(Db.id(row,"id"),0);
            row.put("refundableAmount",finance.dec(row,"amount").subtract(finance.dec(used,"amount")));
            row.put("refundableLessons",finance.dec(row,"lessons").subtract(finance.dec(used,"lessons")));
            row.put("refundableGiftLessons",finance.dec(row,"giftLessons").subtract(finance.dec(used,"giftLessons")));
            var account=db.one("SELECT * FROM lesson_account WHERE id=?",row.get("accountId"));
            row.put("availableBalance",finance.available(account));
            if(finance.dec(row,"refundableLessons").add(finance.dec(row,"refundableGiftLessons")).signum()>0) out.add(row);
        }
        return out;
    }
    Map<String,Object> locked(long id) {
        var row=visible(id); finance.lockStudent(Db.id(row,"studentId"));
        return db.one("SELECT * FROM refund_request WHERE id=? FOR UPDATE",id);
    }
    boolean replay(String key,Map<String,Object> body) {
        var log=db.optional("SELECT request_hash FROM refund_log WHERE request_key=?",key);
        if(log==null) return false;
        if(!log.get("requestHash").equals(growth.hash(body))) throw new ApiException(409,"requestId已用于不同退款内容，请刷新核对");
        return true;
    }
    void version(Map<String,Object> row,Map<String,Object> body) {
        if(!body.containsKey("expectedVersion")) throw ApiException.bad("缺少退款记录版本，请刷新");
        growth.version(row,body);
    }
    void log(long id,String action,String from,String to,String text,String key,Map<String,Object> body) {
        db.update("INSERT INTO refund_log(refund_id,actor_id,actor_name,action,from_status,to_status,text,request_key,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,sec.actor().id(),sec.actor().name(),action,from,to,text,key,growth.hash(body),sec.now());
        sec.audit("REFUND_"+action,"refund="+id+", status="+to);
    }
    @Transactional public Map<String,Object> create(Map<String,Object> body) {
        access(); long renewalId=Db.number(body,"renewalId"); var renewal=source(renewalId,true);
        String key=growth.requestKey("refund-create:"+renewalId,body,true);
        if(replay(key,body)) return detail(Db.id(db.one("SELECT refund_id FROM refund_log WHERE request_key=?",key),"refundId"));
        refundable(renewal);
        if(body.get("lessons")==null||body.get("giftLessons")==null) throw ApiException.bad("请填写退回的购买与赠送课时，未退部分填写0");
        long amount=Db.number(body,"amount"); BigDecimal lessons=Db.decimal(body,"lessons"),gifts=Db.decimal(body,"giftLessons");
        String reason=Db.required(body,"reason",1000); limits(renewal,0,amount,lessons,gifts);
        long id=db.insert("INSERT INTO refund_request(renewal_id,account_id,amount,lessons,gift_lessons,reason,status,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,'REQUESTED',?,?,?)",renewalId,renewal.get("accountId"),amount,lessons,gifts,reason,sec.actor().id(),sec.now(),sec.now());
        log(id,"CREATE",null,"REQUESTED",reason,key,body); return detail(id);
    }
    @Transactional public Map<String,Object> review(long id,Map<String,Object> body) {
        sec.admin(); var row=locked(id); String key=growth.requestKey("refund-review:"+id,body,true);
        if(replay(key,body)) return detail(id);
        version(row,body); if(!row.get("status").equals("REQUESTED")) throw ApiException.bad("仅待审核的退款申请可审核");
        if(!(body.get("approved") instanceof Boolean)) throw ApiException.bad("请选择审核通过或驳回");
        String note=Db.required(body,"note",1000),target=Boolean.TRUE.equals(body.get("approved"))?"APPROVED":"REJECTED";
        if(target.equals("APPROVED")) {
            var renewal=source(Db.id(row,"renewalId"),true);
            refundable(renewal);
            limits(renewal,id,Db.id(row,"amount"),finance.dec(row,"lessons"),finance.dec(row,"giftLessons"));
            if(finance.available(finance.account(Db.id(row,"accountId"))).compareTo(finance.dec(row,"lessons").add(finance.dec(row,"giftLessons")))<0) throw ApiException.bad("账户可用课时不足，无法预留退款课时，请核对已消耗课时");
        }
        db.update("UPDATE refund_request SET status=?,reviewed_by=?,reviewed_at=?,review_note=?,updated_at=?,version=version+1 WHERE id=?",target,sec.actor().id(),sec.now(),note,sec.now(),id);
        log(id,"REVIEW","REQUESTED",target,note,key,body); return detail(id);
    }
    @Transactional public Map<String,Object> cancel(long id,Map<String,Object> body) {
        access(); var row=locked(id);
        if(!sec.isAdmin()&&sec.actor().id()!=Db.id(row,"createdBy")) throw ApiException.forbidden();
        String key=growth.requestKey("refund-cancel:"+id,body,true); if(replay(key,body)) return detail(id);
        version(row,body); if(!List.of("REQUESTED","APPROVED").contains(row.get("status"))) throw ApiException.bad("仅待审核或待线下退款的申请可取消");
        String note=Db.required(body,"note",1000);
        db.update("UPDATE refund_request SET status='CANCELLED',updated_at=?,version=version+1 WHERE id=?",sec.now(),id);
        log(id,"CANCEL",row.get("status").toString(),"CANCELLED",note,key,body); return detail(id);
    }
    @Transactional public Map<String,Object> confirm(long id,Map<String,Object> body) {
        sec.admin(); var row=locked(id); String key=growth.requestKey("refund-confirm:"+id,body,true);
        if(replay(key,body)) return detail(id);
        version(row,body); if(!row.get("status").equals("APPROVED")) throw ApiException.bad("仅审核通过的申请可确认线下退款");
        if(!Boolean.TRUE.equals(body.get("confirmed"))) throw ApiException.bad("须明确确认已经完成线下退款，此操作不会自动转账");
        String method=Db.required(body,"paymentMethod",30); Db.choice(method,"WECHAT_OFFLINE","ALIPAY","CASH","BANK");
        String reference=Db.required(body,"paymentReference",200),note=Db.required(body,"note",1000);
        refundable(source(Db.id(row,"renewalId"),true));
        db.update("UPDATE refund_request SET status='CONFIRMED',confirmed_by=?,confirmed_at=?,payment_method=?,payment_reference=?,confirmation_note=?,updated_at=?,version=version+1 WHERE id=?",sec.actor().id(),sec.now(),method,reference,note,sec.now(),id);
        long ledger=finance.ledger(finance.account(Db.id(row,"accountId")),"REFUND",finance.dec(row,"lessons").negate(),finance.dec(row,"giftLessons").negate(),BigDecimal.ZERO,"线下退款 #"+id+" · 原续费 #"+row.get("renewalId"),"refund:"+id,null);
        db.update("UPDATE refund_request SET ledger_id=? WHERE id=?",ledger,id);
        log(id,"CONFIRM","APPROVED","CONFIRMED",method+" · "+reference+" · "+note,key,body); return detail(id);
    }
}
