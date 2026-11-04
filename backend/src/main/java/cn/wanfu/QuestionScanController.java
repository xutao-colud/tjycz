package cn.wanfu;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teaching-capture")
public class QuestionScanController {
    final QuestionScanService scans;
    public QuestionScanController(QuestionScanService scans){this.scans=scans;}
    @GetMapping("/scan-status") public Object status(){return scans.status();}
    @PostMapping("/jobs") public Object create(@RequestBody Map<String,Object> body){return ResponseEntity.accepted().body(scans.create(body));}
    @GetMapping("/jobs") public Object list(@RequestParam(required=false)Long studentId){return scans.list(studentId);}
    @GetMapping("/jobs/{id}") public Object get(@PathVariable long id){return scans.get(id);}
    @PatchMapping("/jobs/{id}/questions") public Object edit(@PathVariable long id,@RequestBody Map<String,Object> body){return scans.edit(id,body);}
    @PostMapping("/jobs/{id}/import") public Object importItems(@PathVariable long id,@RequestBody Map<String,Object> body){return scans.importItems(id,body);}
    @PostMapping("/jobs/{id}/retry") public Object retry(@PathVariable long id,@RequestBody Map<String,Object> body){return scans.retry(id,body);}
}
