package cn.wanfu;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampusReportService {
    final Db db;final Security sec;final QueryService query;final GrowthService growth;final ClassReportService classes;
    public CampusReportService(Db db,Security sec,QueryService query,GrowthService growth,ClassReportService classes) {this.db=db;this.sec=sec;this.query=query;this.growth=growth;this.classes=classes;}
    Map<String,Object> page(LocalDate date,Long campusId) {
        sec.admin();var campuses=db.list("SELECT id,name FROM campus WHERE "+sec.campusScope("id")+" ORDER BY id");
        if(campusId==null&&!campuses.isEmpty())campusId=Db.id(campuses.get(0),"id");
        var out=new LinkedHashMap<String,Object>();out.put("date",date);out.put("campuses",campuses);out.put("campusId",campusId);out.put("readOnly",date.isAfter(sec.today()));out.put("summary",null);out.put("classes",List.of());out.put("overview",Map.of());
        if(campusId==null)return out;
        sec.campus(campusId);final long campus=campusId;
        var roster=query.students(null,null,date).stream().filter(s->Db.id(s,"campusId")==campus).toList();
        var classrooms=query.classes().stream().filter(c->Db.id(c,"campusId")==campus).toList();
        var teachers=query.teacherAttendance(date).stream().filter(t->Db.id(t,"campusId")==campus).toList();
        var overview=ClassReportService.overview(roster);overview.put("classCount",classrooms.size());overview.put("teacherCount",teachers.size());overview.put("checkedInTeacherCount",teachers.stream().filter(t->t.get("checkInTime")!=null).count());
        List<Map<String,Object>> cards=new ArrayList<>();
        for(var classroom:classrooms) {
            long id=Db.id(classroom,"id");var card=new LinkedHashMap<>(classroom);
            card.put("overview",ClassReportService.overview(roster.stream().filter(s->Db.id(s,"classId")==id).toList()));card.put("report",classes.report(id,date));card.put("comments",classes.comments(id,date));cards.add(card);
        }
        out.put("overview",overview);out.put("classes",cards);out.put("summary",summary(campus,date));return out;
    }
    Map<String,Object> summary(long campus,LocalDate date) {return db.optional("SELECT r.*,c.name campus_name FROM campus_daily_report r JOIN campus c ON c.id=r.campus_id WHERE r.campus_id=? AND r.business_date=?",campus,date);}
    @Transactional public Map<String,Object> save(long campus,LocalDate date,Map<String,Object> body) {
        sec.admin();sec.campus(campus);growth.pastOrToday(date);
        if(!Set.of("content","followUp","nextPlan","expectedVersion").containsAll(body.keySet()))throw ApiException.bad("仅可保存总结、跟进事项、后续安排与版本，记录人由系统确定");
        String content=Db.required(body,"content",6000),followUp=Db.text(body,"followUp"),nextPlan=Db.text(body,"nextPlan");
        if(followUp.length()>2000||nextPlan.length()>2000)throw ApiException.bad("跟进事项和后续安排分别最多2000字");
        if(!body.containsKey("expectedVersion"))throw ApiException.bad("缺少总结版本，请刷新后重试");
        int expected=Db.integer(body,"expectedVersion",-1);if(expected<0)throw ApiException.bad("总结版本无效");
        db.one("SELECT id FROM campus WHERE id=? FOR UPDATE",campus);sec.campus(campus);
        var old=summary(campus,date);
        if(old!=null&&content.equals(old.get("content"))&&followUp.equals(old.get("followUp"))&&nextPlan.equals(old.get("nextPlan")))return old;
        if(expected!=(old==null?0:((Number)old.get("version")).intValue()))throw new ApiException(409,"校区总结已由其他人更新，请刷新后核对再保存");
        var actor=sec.actor();long id;
        if(old==null)id=db.insert("INSERT INTO campus_daily_report(campus_id,business_date,content,follow_up,next_plan,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",campus,date,content,followUp,nextPlan,actor.id(),actor.name(),sec.now(),actor.id(),actor.name(),sec.now());
        else {id=Db.id(old,"id");db.update("UPDATE campus_daily_report SET content=?,follow_up=?,next_plan=?,updated_by=?,updated_name=?,updated_at=?,version=version+1 WHERE id=?",content,followUp,nextPlan,actor.id(),actor.name(),sec.now(),id);}
        sec.auditCampus(campus);sec.audit("CAMPUS_REPORT_SAVE","campus="+campus+", date="+date+", report="+id);return summary(campus,date);
    }
}
