package cn.wanfu;

import java.util.*;
import java.time.LocalDate;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api")
public class ApiController {
    final Db db; final Security sec; final FinanceService finance; final LearningService learn; final QueryService queries; final ManagementService management; final FileService files; final Bootstrap bootstrap; final PasswordTransportService passwordTransport; final AttendanceLocationService locations;
    public ApiController(Db db,Security sec,FinanceService finance,LearningService learn,QueryService queries,ManagementService management,FileService files,Bootstrap bootstrap,PasswordTransportService passwordTransport,AttendanceLocationService locations) { this.db=db; this.sec=sec; this.finance=finance; this.learn=learn; this.queries=queries; this.management=management; this.files=files; this.bootstrap=bootstrap; this.passwordTransport=passwordTransport; this.locations=locations; }
    @GetMapping("/health") public Object health() { return Map.of("status","UP","demo",sec.local()); }
    @PostMapping("/auth/demo") public Object demo(@RequestBody Map<String,Object> b) {
        if(!sec.local()) throw new ApiException(404,"演示登录未启用"); String role=Db.required(b,"role",16); Db.choice(role,"ADMIN","CAMPUS_ADMIN","TEACHER","PARENT");
        bootstrap.seedDay(); var user=db.one("SELECT id,name,role FROM app_user WHERE username=?",role.toLowerCase(Locale.ROOT)); return sec.session(user,true);
    }
    @GetMapping("/auth/challenge") public ResponseEntity<Map<String,Object>> challenge(@RequestParam(defaultValue="LOGIN") String purpose) { return ResponseEntity.ok().header("Cache-Control","no-store").body(passwordTransport.challenge(purpose)); }
    @PostMapping("/auth/login") public Object login(@RequestBody Map<String,Object> b) {
        String password=passwordTransport.decode(b,PasswordTransportService.Purpose.LOGIN);
        String username=Db.required(b,"username",80);
        if(sec.local() && username.equals("admin") && password.equals("admin")) {
            String role=Db.text(b,"role");
            return demo(Map.of("role",role.isBlank()?"ADMIN":role));
        }
        var user=db.optional("SELECT * FROM app_user WHERE username=?",username);
        // Constant cost for unknown usernames as well.
        String hash=user==null?"$2a$12$WsD6jQM4KTSffyAHXQzMVurYYyXOrQW3HjNcphVBFtO/TfGKKn2Ga":user.get("passwordHash").toString();
        if(!sec.passwords.matches(password,hash)||user==null) throw new ApiException(401,"用户名或密码错误");
        return sec.session(user,false);
    }
    @GetMapping("/me") public Object me() { return sec.me(); }
    @GetMapping("/campuses") public Object campuses() { return management.campuses(); }
    @PostMapping("/campuses") public Object campus(@RequestBody Map<String,Object> b) { return management.createCampus(b); }
    @PutMapping("/campuses/{id}/check-in-location") public Object campusLocation(@PathVariable long id,@RequestBody Map<String,Object> b) { return locations.configure(id,b); }
    @GetMapping("/dashboard") public Object dashboard(@RequestParam(required=false) String date) { return queries.dashboard(sec.date(date)); }
    @GetMapping("/classes") public Object classes() { return queries.classes(); }
    @PostMapping("/classes") public Object createClass(@RequestBody Map<String,Object> b) { return management.createClass(b); }
    @GetMapping("/students") public Object students(@RequestParam(required=false) Long classId,@RequestParam(required=false) String keyword) { return queries.students(classId,keyword,sec.today()); }
    @GetMapping("/students/{id}") public Object student(@PathVariable long id) { return queries.student(id); }
    @PostMapping("/students") public Object createStudent(@RequestBody Map<String,Object> b) { return queries.student(Db.id(management.createStudent(b),"id")); }
    @GetMapping("/attendance") public Object attendance(@RequestParam(required=false) String date,@RequestParam(required=false) Long classId,@RequestParam(required=false) Long studentId) { return queries.attendance(sec.date(date),classId,studentId); }
    @GetMapping("/teacher-attendance") public Object teacherAttendance(@RequestParam(required=false) String date) { return queries.teacherAttendance(sec.date(date)); }
    @PostMapping("/teacher-attendance/check-in") public Object teacherCheckIn(@RequestBody Map<String,Object> b) { return management.teacherCheckIn(b); }
    private Object batchAttendance(Map<String,Object> b,boolean checkout) {
        sec.staff(); List<Long> ids=Db.ids(b,"studentIds"); for(long sid:ids) sec.student(sid); LocalDate date=sec.date(Db.text(b,"date"));
        List<Object> results=new ArrayList<>();
        for(long sid:ids) {
            try { Map<String,Object> result=finance.attendance(List.of(sid),date,checkout,Db.text(b,"historicalReason"),b); results.addAll((List<?>)result.get("results")); }
            catch(ApiException e) { results.add(Map.of("studentId",sid,"success",false,"message",e.getMessage())); }
            catch(org.springframework.dao.DataAccessException e) { results.add(Map.of("studentId",sid,"success",false,"message","数据正在处理，请重试")); }
        }
        return Map.of("results",results);
    }
    @PostMapping("/attendance/check-in") public Object checkin(@RequestBody Map<String,Object> b) { return batchAttendance(b,false); }
    @PostMapping("/attendance/check-out") public Object checkout(@RequestBody Map<String,Object> b) { return batchAttendance(b,true); }
    @GetMapping("/homework") public Object homework(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId,@RequestParam(required=false) String subject,@RequestParam(required=false) String status,@RequestParam(defaultValue="true") boolean attachments) { return queries.homework(sec.date(date),studentId,subject,status,attachments); }
    @PostMapping("/homework/tasks") public Object task(@RequestBody Map<String,Object> b) { return learn.task(b); }
    @PostMapping("/homework/{id}/status") public Object status(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.homeworkStatus(id,b); }
    @PostMapping("/homework/{id}/review") public Object homeworkReview(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.reviewHomework(id,b); }
    @PostMapping("/homework/{id}/attachments") public Object homeworkFiles(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.homeworkFiles(id,Db.optionalIds(b,"fileIds")); }
    @GetMapping("/mistakes") public Object mistakes(@RequestParam(required=false) Long studentId,@RequestParam(required=false) String subject,@RequestParam(required=false) String status) { return queries.mistakes(studentId,subject,status); }
    @PostMapping("/mistakes") public Object createMistake(@RequestBody Map<String,Object> b) { return learn.createMistake(b); }
    @PostMapping("/mistakes/{id}/correct") public Object correct(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.correctMistake(id,b); }
    @PostMapping("/mistakes/{id}/review") public Object mistakeReview(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.reviewMistake(id,b); }
    @PostMapping("/mistakes/reviews/{reviewId}/complete") public Object reviewComplete(@PathVariable long reviewId) { return learn.completeReview(reviewId); }
    @GetMapping("/fitness") public Object fitness(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return queries.fitness(sec.date(date),studentId); }
    @PostMapping("/fitness/batch") public Object fitnessBatch(@RequestBody Map<String,Object> b) { return learn.fitness(b); }
    @GetMapping("/points") public Object points(@RequestParam(required=false) Long studentId) { return queries.points(studentId); }
    @PostMapping("/points") public Object manualPoints(@RequestBody Map<String,Object> b) { return finance.manualPoints(b); }
    @GetMapping("/courses") public Object courses() { return queries.courses(); }
    @PostMapping("/courses") public Object createCourse(@RequestBody Map<String,Object> b) { return management.createCourse(b); }
    @GetMapping("/accounts") public Object accounts(@RequestParam(required=false) Long studentId) { return queries.accounts(studentId); }
    @GetMapping("/lesson-ledger") public Object ledger(@RequestParam(required=false) Long studentId,@RequestParam(required=false) Long accountId) { return queries.ledger(studentId,accountId); }
    @GetMapping("/renewals") public Object renewals(@RequestParam(required=false) Long studentId) { return queries.renewals(studentId); }
    @PostMapping("/renewals") public Object renewal(@RequestBody Map<String,Object> b) { return finance.renewal(b); }
    @PostMapping("/renewals/{id}/confirm") public Object confirm(@PathVariable long id) { return finance.confirm(id); }
    @PostMapping("/lesson-ledger/{id}/reverse") public Object reverse(@PathVariable long id,@RequestBody Map<String,Object> b) { return finance.reverse(id,Db.required(b,"reason",1000)); }
    @GetMapping("/settlements") public Object settlements() { return queries.settlements(); }
    @PostMapping("/settlements/{id}/retry") public Object retry(@PathVariable long id) { return finance.retry(id); }
    @GetMapping("/schedules") public Object schedules(@RequestParam(required=false) String date) { return queries.schedules(sec.date(date)); }
    @PostMapping("/schedules") public Object schedule(@RequestBody Map<String,Object> b) { return management.schedule(b); }
    @GetMapping("/enrollments") public Object enrollments(@RequestParam(required=false) Long studentId) { return queries.enrollments(studentId); }
    @PostMapping("/enrollments") public Object enrollment(@RequestBody Map<String,Object> b) { return finance.enrollment(Db.number(b,"studentId"),Db.number(b,"courseId")); }
    @GetMapping("/summaries") public Object summaries(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return queries.summaries(sec.date(date),studentId); }
    @PostMapping("/summaries/generate") public Object generate(@RequestBody Map<String,Object> b) { return learn.generate(b); }
    @PatchMapping("/summaries/{id}") public Object summaryEdit(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.editSummary(id,b); }
    @PostMapping("/summaries/{id}/publish") public Object publish(@PathVariable long id,@RequestBody(required=false) Map<String,Object> b) { return learn.publish(id,b!=null&&Boolean.TRUE.equals(b.get("confirmedConcerns"))); }
    @PostMapping("/summaries/{id}/feedback") public Object feedback(@PathVariable long id,@RequestBody Map<String,Object> b) { return learn.feedback(id,b); }
    @PostMapping("/files") public Object upload(@RequestParam("file") MultipartFile file) throws IOException { return files.upload(file); }
    @GetMapping("/files/{id}") public ResponseEntity<Resource> download(@PathVariable long id) { return files.download(id); }
    @GetMapping("/settings") public Object settings() { return management.settings(); }
    @PatchMapping("/settings") public Object editSettings(@RequestBody Map<String,Object> b) { return management.settings(b); }
    @GetMapping("/users") public Object users() { return management.users(); }
    @PutMapping("/users/{id}/access") public Object access(@PathVariable long id,@RequestBody Map<String,Object> b) { return management.access(id,b); }
    @PostMapping("/users") public Object user(@RequestBody Map<String,Object> b) { return management.createUser(b); }
    @GetMapping("/audit-logs") public Object audit() { sec.admin(); return db.list("SELECT id,actor_id,actor_name,campus_id,action,detail,created_at FROM audit_log WHERE "+sec.campusScope("campus_id")+" ORDER BY id DESC LIMIT 1000"); }
}
