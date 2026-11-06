package cn.wanfu;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SummaryReviewController {
    final LearningService learn; final DailyFeedbackService feedback; final Security sec;
    public SummaryReviewController(LearningService learn,DailyFeedbackService feedback,Security sec) { this.learn=learn;this.feedback=feedback;this.sec=sec; }
    @GetMapping("/daily-feedback") public Object feedback(@RequestParam(required=false) String date,@RequestParam(required=false) Long studentId) { return feedback.list(sec.date(date),studentId); }
    @PutMapping("/students/{id}/daily-feedback") public Object saveFeedback(@PathVariable long id,@RequestBody Map<String,Object> body) { return feedback.save(id,sec.date(Db.text(body,"date")),body); }
    @GetMapping("/class-satisfaction") public Object satisfaction(@RequestParam(required=false) String date,@RequestParam(required=false) Long classId) { return feedback.classes(sec.date(date),classId); }
    @GetMapping("/summaries/{id}") public Object detail(@PathVariable long id) { return learn.summary(id); }
    @PostMapping("/summaries/{id}/submit-review") public Object submit(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.submitSummary(id,body); }
    @PostMapping("/summaries/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.reviewSummary(id,body); }
    @PostMapping("/summaries/{id}/withdraw-review") public Object withdraw(@PathVariable long id,@RequestBody Map<String,Object> body) { return learn.withdrawSummary(id,body); }
    @GetMapping("/mistakes/{id}") public Object mistake(@PathVariable long id) { return learn.mistake(id); }
}
