package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/class-reports")
public class ClassReportController {
    final ClassReportService reports; final Security sec;
    public ClassReportController(ClassReportService reports,Security sec) {this.reports=reports;this.sec=sec;}
    @GetMapping public Object page(@RequestParam(required=false) String date,@RequestParam(required=false) Long classId) {return reports.page(sec.date(date),classId);}
    @PutMapping("/{classId}") public Object save(@PathVariable long classId,@RequestParam(required=false) String date,@RequestBody Map<String,Object> body) {return reports.save(classId,sec.date(date),body);}
    @PostMapping("/{classId}/comments") public Object comment(@PathVariable long classId,@RequestParam(required=false) String date,@RequestBody Map<String,Object> body) {return reports.comment(classId,sec.date(date),body);}
}
