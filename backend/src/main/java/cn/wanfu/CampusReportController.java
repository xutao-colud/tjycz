package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campus-reports")
public class CampusReportController {
    final CampusReportService reports;final Security sec;
    public CampusReportController(CampusReportService reports,Security sec) {this.reports=reports;this.sec=sec;}
    @GetMapping public Object page(@RequestParam(required=false) String date,@RequestParam(required=false) Long campusId) {return reports.page(sec.date(date),campusId);}
    @PutMapping("/{campusId}") public Object save(@PathVariable long campusId,@RequestParam(required=false) String date,@RequestBody Map<String,Object> body) {return reports.save(campusId,sec.date(date),body);}
}
