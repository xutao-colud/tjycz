package cn.wanfu;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassReportService {
    final Db db; final Security sec; final QueryService query; final GrowthService growth;
    public ClassReportService(Db db,Security sec,QueryService query,GrowthService growth) {this.db=db;this.sec=sec;this.query=query;this.growth=growth;}

    Map<String,Object> page(LocalDate date,Long classId) {
        sec.staff(); var classes=query.classes();
        if(classId==null&&!classes.isEmpty()) classId=Db.id(classes.get(0),"id");
        Map<String,Object> out=new LinkedHashMap<>();out.put("date",date);out.put("classes",classes);out.put("classId",classId);out.put("readOnly",date.isAfter(sec.today()));out.put("report",null);out.put("comments",List.of());
        if(classId==null) {out.put("overview",Map.of());return out;}
        sec.schoolClass(classId);
        var students=query.students(classId,null,date);
        out.put("overview",overview(students));
        out.put("report",report(classId,date));out.put("comments",comments(classId,date));return out;
    }
    static Map<String,Object> overview(List<Map<String,Object>> students) {
        var stats=new LinkedHashMap<String,Object>();stats.put("studentCount",students.size());
        stats.put("arrivedCount",students.stream().filter(s->List.of("PRESENT","LEFT").contains(s.get("attendanceStatus"))).count());
        stats.put("leaveCount",students.stream().filter(s->s.get("attendanceStatus").equals("ON_LEAVE")).count());
        stats.put("homeworkCount",students.stream().mapToLong(s->((Number)s.get("homeworkTotal")).longValue()).sum());
        stats.put("completedHomeworkCount",students.stream().mapToLong(s->((Number)s.get("homeworkCompleted")).longValue()).sum());
        return stats;
    }
    Map<String,Object> report(long classId,LocalDate date) {
        return db.optional("SELECT r.*,cl.name class_name,cl.campus_id,cp.name campus_name FROM class_daily_report r JOIN school_class cl ON cl.id=r.class_id JOIN campus cp ON cp.id=cl.campus_id WHERE r.class_id=? AND r.business_date=?",classId,date);
    }
    List<Map<String,Object>> comments(long classId,LocalDate date) {
        return db.list("SELECT id,class_id,business_date,text,actor_id,actor_name,actor_role,created_at FROM class_report_comment WHERE class_id=? AND business_date=? ORDER BY id",classId,date);
    }
    @Transactional public Map<String,Object> comment(long classId,LocalDate date,Map<String,Object> body) {
        sec.admin();sec.schoolClass(classId);growth.pastOrToday(date);
        if(!Set.of("text","requestId").containsAll(body.keySet())) throw ApiException.bad("仅可提交点评内容和请求标识，点评人由系统确定");
        String text=Db.required(body,"text",2000),key=sec.actor().id()+":"+Db.required(body,"requestId",70);
        db.one("SELECT id FROM school_class WHERE id=? FOR UPDATE",classId);sec.schoolClass(classId);
        var old=db.optional("SELECT * FROM class_report_comment WHERE request_key=?",key);
        if(old!=null) {
            if(Db.id(old,"classId")!=classId||!old.get("businessDate").equals(date)||!old.get("text").equals(text)) throw new ApiException(409,"该请求标识已用于另一条点评，请刷新后重新提交");
            old.remove("requestKey");return old;
        }
        var actor=sec.actor();
        long id=db.insert("INSERT INTO class_report_comment(class_id,business_date,text,actor_id,actor_name,actor_role,created_at,request_key) VALUES(?,?,?,?,?,?,?,?)",classId,date,text,actor.id(),actor.name(),actor.role(),sec.now(),key);
        sec.audit("CLASS_REPORT_COMMENT","class="+classId+", date="+date+", comment="+id);
        return db.one("SELECT id,class_id,business_date,text,actor_id,actor_name,actor_role,created_at FROM class_report_comment WHERE id=?",id);
    }
    @Transactional public Map<String,Object> save(long classId,LocalDate date,Map<String,Object> body) {
        sec.staff();sec.schoolClass(classId);growth.pastOrToday(date);
        if(!Set.of("content","followUp","nextPlan","expectedVersion").containsAll(body.keySet())) throw ApiException.bad("仅可保存日报内容、跟进事项、后续安排与版本，记录人由系统确定");
        String content=Db.required(body,"content",6000),followUp=Db.text(body,"followUp"),nextPlan=Db.text(body,"nextPlan");
        if(followUp.length()>2000||nextPlan.length()>2000) throw ApiException.bad("跟进事项和后续安排分别最多2000字");
        if(!body.containsKey("expectedVersion")) throw ApiException.bad("缺少记录版本，请刷新后重试");
        int expected=Db.integer(body,"expectedVersion",-1);if(expected<0) throw ApiException.bad("记录版本无效");
        db.one("SELECT id FROM school_class WHERE id=? FOR UPDATE",classId);sec.schoolClass(classId);
        var old=report(classId,date);
        if(old!=null&&content.equals(old.get("content"))&&followUp.equals(old.get("followUp"))&&nextPlan.equals(old.get("nextPlan"))) return old;
        if(expected!=(old==null?0:((Number)old.get("version")).intValue())) throw new ApiException(409,"班级日报已由其他人更新，请刷新后核对再保存");
        var actor=sec.actor();
        long id;
        if(old==null) id=db.insert("INSERT INTO class_daily_report(class_id,business_date,content,follow_up,next_plan,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",classId,date,content,followUp,nextPlan,actor.id(),actor.name(),sec.now(),actor.id(),actor.name(),sec.now());
        else {id=Db.id(old,"id");db.update("UPDATE class_daily_report SET content=?,follow_up=?,next_plan=?,updated_by=?,updated_name=?,updated_at=?,version=version+1 WHERE id=?",content,followUp,nextPlan,actor.id(),actor.name(),sec.now(),id);}
        sec.audit("CLASS_REPORT_SAVE","class="+classId+", date="+date+", report="+id);
        return report(classId,date);
    }
}
