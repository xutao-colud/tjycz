package cn.wanfu;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PickupAlertService {
    final Db db; final Security sec;
    public PickupAlertService(Db db,Security sec) { this.db=db; this.sec=sec; }
    void lock(long sid) { sec.student(sid); db.one("SELECT id FROM student WHERE id=? FOR UPDATE",sid); sec.student(sid); }
    String query() { return "SELECT a.id,a.student_id,s.name student_name,s.class_id,cl.name class_name,cl.campus_id,cp.name campus_name,a.business_date,a.authorization_id,a.source,a.pickup_name,a.phone_last4,a.reason,a.status,a.version,a.created_at,a.updated_at,s.parent_phone FROM pickup_alert a JOIN student s ON s.id=a.student_id JOIN school_class cl ON cl.id=s.class_id JOIN campus cp ON cp.id=cl.campus_id"; }
    Map<String,Object> detail(long id) {
        var row=db.one(query()+" WHERE a.id=?",id); sec.student(Db.id(row,"studentId"));
        boolean staff=!sec.actor().role().equals("PARENT");
        row.put("canHandle",staff&&!row.get("status").equals("RESOLVED"));
        if(!staff) row.remove("parentPhone");
        row.put("logs",db.list("SELECT id,action,actor_name,actor_role,note,created_at FROM pickup_alert_log WHERE alert_id=? ORDER BY id",id));
        row.put("notificationStatus","IN_APP"); return row;
    }
    List<Map<String,Object>> list(LocalDate date,Long student) {
        if(student!=null) sec.student(student);
        List<Object> args=new ArrayList<>(List.of(date));
        String sql="SELECT a.id FROM pickup_alert a JOIN student s ON s.id=a.student_id WHERE a.business_date=? AND s.privacy_erased_at IS NULL AND "+sec.scope("s");
        if(student!=null) { sql+=" AND s.id=?"; args.add(student); }
        return db.list(sql+" ORDER BY CASE WHEN a.status='RESOLVED' THEN 1 ELSE 0 END,a.id DESC",args.toArray()).stream().map(r->detail(Db.id(r,"id"))).toList();
    }
    void invalidate(long sid) { db.update("UPDATE pickup_verification SET expires_at=? WHERE student_id=? AND used_at IS NULL AND expires_at>?",sec.now().minusSeconds(1),sid,sec.now()); }
    void log(long id,String action,String note) {
        var actor=sec.actor();
        db.update("INSERT INTO pickup_alert_log(alert_id,action,actor_id,actor_name,actor_role,note,created_at) VALUES(?,?,?,?,?,?,?)",id,action,actor.id(),actor.name(),actor.role(),note,sec.now());
        sec.audit("PICKUP_ALERT_"+action,"alert="+id);
    }
    @Transactional public Map<String,Object> create(Map<String,Object> body) {
        sec.staff(); long sid=Db.number(body,"studentId"); lock(sid);
        LocalDate date=sec.date(Db.text(body,"date")); if(!date.equals(sec.today())) throw ApiException.bad("接送现场异常只能登记当天，请使用关注事项补充历史说明");
        String name=Db.required(body,"pickupName",80),last4=Db.text(body,"phoneLast4"),reason=Db.required(body,"reason",1000);
        if(!last4.isBlank()&&!last4.matches("[0-9]{4}")) throw ApiException.bad("电话后四位须为4位数字，也可留空");
        String key="manual:"+sec.actor().id()+":"+Db.required(body,"requestId",80),hash=sec.hash(sid+"\n"+date+"\n"+name+"\n"+last4+"\n"+reason);
        var old=db.optional("SELECT id,request_hash FROM pickup_alert WHERE source_key=?",key);
        if(old!=null) { if(!hash.equals(old.get("requestHash"))) throw new ApiException(409,"requestId已用于其他异常内容"); return detail(Db.id(old,"id")); }
        long id=db.insert("INSERT INTO pickup_alert(student_id,business_date,source,source_key,request_hash,pickup_name,phone_last4,reason,created_by,created_at,updated_at) VALUES(?,?,'MANUAL',?,?,?,?,?,?,?,?)",sid,date,key,hash,name,last4,reason,sec.actor().id(),sec.now(),sec.now());
        invalidate(sid); log(id,"OPEN","现场异常已登记，暂不放行："+reason); return detail(id);
    }
    @Transactional public Map<String,Object> handle(long id,Map<String,Object> body) {
        sec.staff(); var initial=detail(id); lock(Db.id(initial,"studentId"));
        var row=db.one("SELECT * FROM pickup_alert WHERE id=? FOR UPDATE",id);
        String action=Db.required(body,"action",20),note=Db.required(body,"note",1000); Db.choice(action,"HOLD","RESOLVED");
        if(Db.integer(body,"expectedVersion",-1)!=Db.integer(row,"version",0)) throw new ApiException(409,"异常记录已更新，请刷新核对");
        if(row.get("status").equals("RESOLVED")) throw ApiException.bad("异常已处理完毕，不能覆盖原处理记录");
        db.update("UPDATE pickup_alert SET status=?,version=version+1,updated_at=? WHERE id=?",action,sec.now(),id);
        if(action.equals("HOLD")) invalidate(Db.id(initial,"studentId"));
        log(id,action,note); return detail(id);
    }
    void rejected(long sid,long authorization,String reason) {
        sec.staff(); lock(sid);
        String key="verify:"+sid+":"+sec.today()+":"+authorization;
        var old=db.optional("SELECT id,status FROM pickup_alert WHERE source_key=? FOR UPDATE",key);
        long id;
        if(old==null) {
            var pickup=db.one("SELECT pickup_name,phone FROM pickup_authorization WHERE id=? AND student_id=?",authorization,sid);
            String phone=pickup.get("phone").toString(),last4=phone.length()>=4?phone.substring(phone.length()-4):"";
            id=db.insert("INSERT INTO pickup_alert(student_id,business_date,authorization_id,source,source_key,request_hash,pickup_name,phone_last4,reason,created_by,created_at,updated_at) VALUES(?,?,?,'VERIFICATION',?,?,?,?,?,?,?,?)",sid,sec.today(),authorization,key,sec.hash(key),pickup.get("pickupName"),last4,reason,sec.actor().id(),sec.now(),sec.now());
        } else { id=Db.id(old,"id"); db.update("UPDATE pickup_alert SET status=CASE WHEN status='HOLD' THEN 'HOLD' ELSE 'OPEN' END,reason=?,version=version+1,updated_at=? WHERE id=?",reason,sec.now(),id); }
        invalidate(sid); log(id,"REJECTED",reason);
    }
    void clearVerified(long sid,long authorization) {
        // A new valid code only clears matching automatic failures; a manual hold still requires staff handling.
        for(var row:db.list("SELECT id FROM pickup_alert WHERE student_id=? AND business_date=? AND authorization_id=? AND source='VERIFICATION' AND status='OPEN' FOR UPDATE",sid,sec.today(),authorization)) {
            long id=Db.id(row,"id"); db.update("UPDATE pickup_alert SET status='RESOLVED',version=version+1,updated_at=? WHERE id=?",sec.now(),id);
            log(id,"VERIFIED","已重新完成当前有效授权核验，解除本授权的自动核验告警");
        }
    }
    boolean blocked(long sid,LocalDate date) { return db.count("SELECT COUNT(*) FROM pickup_alert WHERE student_id=? AND business_date=? AND status IN ('OPEN','HOLD')",sid,date)>0; }
    void requireClear(long sid,LocalDate date) {
        if(blocked(sid,date)) throw ApiException.bad("接送异常尚未处理，暂不离校；请联系家长并处理异常后重新核验");
    }
}
