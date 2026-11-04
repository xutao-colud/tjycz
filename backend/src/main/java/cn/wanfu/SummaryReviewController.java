package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SummaryReviewController {
    final LearningService learn;
    public SummaryReviewController(LearningService learn) { this.learn=learn; }
    @GetMapping("/summaries/{id}") public Object detail(@PathVariable long id) { return learn.summary(id); }
    @PostMapping("/summaries/{id}/submit-review") public Object submit(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.submitSummary(id,body); }
    @PostMapping("/summaries/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.reviewSummary(id,body); }
    @PostMapping("/summaries/{id}/withdraw-review") public Object withdraw(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.withdrawSummary(id,body); }
    @GetMapping("/mistakes/{id}") public Object mistake(@PathVariable long id) { return learn.mistake(id); }
}
