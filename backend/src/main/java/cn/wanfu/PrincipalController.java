package cn.wanfu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PrincipalController {
    final Db db;
    final Security sec;
    final QueryService queries;
    final AttendanceSafetyService safety;
    final RefundService refunds;

    public PrincipalController(Db db, Security sec, QueryService queries, AttendanceSafetyService safety, RefundService refunds) {
        this.db=db; this.sec=sec; this.queries=queries; this.safety=safety; this.refunds=refunds;
    }

    @GetMapping("/principal-overview")
    public Object overview(@RequestParam(required=false) String date) {
        sec.admin();
        LocalDate day=sec.date(date);
        String scope=queries.activeScope();
        var students=db.list("SELECT roster.id,roster.name,roster.class_id,roster.class_name,roster.campus_name,roster.attendance_status,roster.check_in_time,roster.check_out_time FROM ("+
            queries.studentQuery(day)+" WHERE "+scope+") roster ORDER BY roster.id");
        var byId=new HashMap<Long,Map<String,Object>>();
        for(var student:students) byId.put(Db.id(student,"id"),student);

        var scheduledClasses=new HashSet<Long>();
        var scheduled=db.list("SELECT DISTINCT cl.id FROM schedule sc JOIN school_class cl ON cl.id=sc.class_id JOIN course c ON c.id=sc.course_id WHERE sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE AND "+sec.classScope("cl"),day);
        for(var row:scheduled) scheduledClasses.add(Db.id(row,"id"));
        var enrolled=db.list("SELECT DISTINCT s.id FROM student s JOIN enrollment e ON e.student_id=s.id JOIN schedule sc ON sc.course_id=e.course_id AND sc.class_id=s.class_id JOIN course c ON c.id=sc.course_id WHERE sc.date=? AND sc.enabled=TRUE AND c.enabled=TRUE AND "+scope,day);
        var expectedIds=new HashSet<Long>();
        for(var row:enrolled) expectedIds.add(Db.id(row,"id"));
        var expected=students.stream().filter(s->!s.get("attendanceStatus").equals("ON_LEAVE"))
            .filter(s->!scheduledClasses.contains(Db.id(s,"classId"))||expectedIds.contains(Db.id(s,"id"))).toList();
        var arrived=expected.stream().filter(s->List.of("PRESENT","LEFT").contains(s.get("attendanceStatus"))).toList();
        var present=students.stream().filter(s->s.get("attendanceStatus").equals("PRESENT")).toList();

        var warnings=new LinkedHashMap<Long,Map<String,Object>>();
        for(var account:queries.accounts(null)) {
            long sid=Db.id(account,"studentId");
            if(!byId.containsKey(sid)||new BigDecimal(account.get("availableBalance").toString()).compareTo(new BigDecimal(account.get("warningThreshold").toString()))>0) continue;
            warnings.computeIfAbsent(sid,id->byId.get(id));
        }

        var alerts=db.list("SELECT a.id,a.student_id,s.name student_name,s.class_id,a.business_date,a.reason,a.status FROM pickup_alert a JOIN student s ON s.id=a.student_id WHERE a.business_date=? AND a.status IN ('OPEN','HOLD') AND "+scope+" ORDER BY a.id DESC",day);
        var todos=new ArrayList<Map<String,Object>>();
        for(var row:alerts) todos.add(todo("SAFETY",row,"接送异常",row.get("reason"),"校区值班人员"));
        var overdue=db.list(FollowUpService.query()+" WHERE f.status IN ('OPEN','IN_PROGRESS') AND f.business_date<=? AND f.next_feedback_at<=? AND "+scope+" ORDER BY f.next_feedback_at,f.id",day,sec.now());
        for(var row:overdue) todos.add(todo("FOLLOW_UP",row,"跟进已到期",row.get("title"),row.get("ownerName")));
        var summaries=db.list(queries.learn.summaryQuery()+" WHERE d.date<=? AND d.status='IN_REVIEW' AND "+scope+" ORDER BY d.date,d.id",day);
        for(var row:summaries) {
            if(Objects.equals(row.get("submittedBy"),sec.actor().id())) continue;
            row.put("businessDate",row.get("date"));
            todos.add(todo("SUMMARY",row,"小结待审核","核对教师提交的小结",row.get("submittedName")));
        }
        for(var row:safety.leaves(day,null)) {
            var student=byId.get(Db.id(row,"studentId"));
            if(day.isBefore(sec.today())||student==null||!student.get("attendanceStatus").equals("NOT_ARRIVED")||!Boolean.TRUE.equals(row.get("canReview"))) continue;
            row.put("businessDate",day);
            todos.add(todo("LEAVE",row,"请假待审核",row.get("reason"),"校区审批人"));
        }
        for(var row:refunds.list(null,"REQUESTED")) {
            if(byId.containsKey(Db.id(row,"studentId"))&&Boolean.TRUE.equals(row.get("canReview")))
                todos.add(todo("REFUND",row,"退款待审核","查看原申请及审核条件","校区审批人"));
        }
        if(day.equals(sec.today())) {
            for(var row:queries.teacherAttendance(day)) {
                if(!row.get("status").equals("NOT_CHECKED_IN")) continue;
                long started=db.count("SELECT COUNT(*) FROM schedule sc JOIN course c ON c.id=sc.course_id JOIN school_class cl ON cl.id=sc.class_id JOIN teacher_class tc ON tc.class_id=cl.id WHERE sc.date=? AND sc.start_time<=? AND sc.enabled=TRUE AND c.enabled=TRUE AND tc.user_id=? AND cl.campus_id=?",
                    day,sec.now().toLocalTime().toString(),row.get("teacherId"),row.get("campusId"));
                if(started>0) todos.add(Map.of("kind","TEACHER","id",row.get("teacherId")+":"+row.get("campusId"),"title","教师尚未签到",
                    "reason",row.get("campusName")+"已有课程开始","owner",row.get("teacherName")));
            }
        }

        var classes=queries.classes();
        for(var row:classes) {
            long id=Db.id(row,"id");
            row.put("expected",expected.stream().filter(s->Db.id(s,"classId")==id).count());
            row.put("arrived",arrived.stream().filter(s->Db.id(s,"classId")==id).count());
            row.put("present",present.stream().filter(s->Db.id(s,"classId")==id).count());
            row.put("alerts",alerts.stream().filter(a->Db.id(a,"classId")==id).count());
        }
        return Map.of("date",day,"expectedCount",expected.size(),"arrived",arrived,"present",present,
            "warnings",warnings.values(),"todos",todos,"classes",classes);
    }

    private Map<String,Object> todo(String kind,Map<String,Object> row,String title,Object reason,Object owner) {
        return Map.of("kind",kind,"id",row.get("id"),"studentId",row.get("studentId"),"studentName",row.get("studentName"),
            "date",Objects.toString(row.get("businessDate"),""),"title",title,"reason",Objects.toString(reason,""),"owner",Objects.toString(owner,""));
    }
}
