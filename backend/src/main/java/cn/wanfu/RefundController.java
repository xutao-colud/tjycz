package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {
    final RefundService refunds;
    public RefundController(RefundService refunds) { this.refunds=refunds; }
    @GetMapping public Object list(@RequestParam(required=false) Long studentId,@RequestParam(required=false) String status) { return refunds.list(studentId,status); }
    @GetMapping("/sources") public Object sources(@RequestParam(required=false) Long studentId) { return refunds.sources(studentId); }
    @GetMapping("/{id}") public Object detail(@PathVariable long id) { return refunds.detail(id); }
    @PostMapping public Object create(@RequestBody Map<String,Object> body) { return refunds.create(body); }
    @PostMapping("/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object> body) { return refunds.review(id,body); }
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable long id,@RequestBody Map<String,Object> body) { return refunds.cancel(id,body); }
    @PostMapping("/{id}/confirm") public Object confirm(@PathVariable long id,@RequestBody Map<String,Object> body) { return refunds.confirm(id,body); }
}
