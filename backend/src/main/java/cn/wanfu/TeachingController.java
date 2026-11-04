package cn.wanfu;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TeachingController {
    final TeachingService teaching;final StudentNotificationService messages;final RewardService rewards;final Security sec;final MistakeBookService books;
    public TeachingController(TeachingService teaching,StudentNotificationService messages,RewardService rewards,Security sec,MistakeBookService books){this.books=books;this.teaching=teaching;this.messages=messages;this.rewards=rewards;this.sec=sec;}
    @PostMapping("/mistake-books") public Object book(@RequestBody Map<String,Object> body)throws java.io.IOException{return books.create(body);}
    @PostMapping("/attendance/photo") public Object photo(@RequestBody Map<String,Object> body){return teaching.photoAttendance(body);}
    @GetMapping("/student-notifications") public Object notifications(@RequestParam(required=false)Long studentId){return messages.list(studentId);}
    @PostMapping("/student-notifications/{id}/read") public Object read(@PathVariable long id){return messages.read(id);}
    @PostMapping("/lesson-grants") public Object grant(@RequestBody Map<String,Object> body){return teaching.grant(body);}
    @GetMapping("/teaching-capture/status") public Object captureStatus(){sec.staff();return Map.of("ocrStatus","NOT_CONFIGURED","wechatStatus","NOT_CONFIGURED","message","拍照后可手工校对；OCR与微信订阅服务等待配置。 ");}
    @PostMapping("/teaching-capture/recognize") public Object recognize(@RequestBody Map<String,Object> body){return teaching.recognize(body);}
    @PostMapping("/teaching-capture/save") public Object saveCapture(@RequestBody Map<String,Object> body){return teaching.saveCapture(body);}
    @GetMapping("/point-presets") public Object presets(){return rewards.presets();}
    @PostMapping("/point-presets") public Object preset(@RequestBody Map<String,Object> body){return rewards.preset(null,body);}
    @PutMapping("/point-presets/{id}") public Object preset(@PathVariable long id,@RequestBody Map<String,Object> body){return rewards.preset(id,body);}
    @PostMapping("/point-awards") public Object award(@RequestBody Map<String,Object> body){return rewards.award(body);}
    @GetMapping("/reward-prizes") public Object prizes(){return rewards.prizes();}
    @PostMapping("/reward-prizes") public Object prize(@RequestBody Map<String,Object> body){return rewards.prize(null,body);}
    @PutMapping("/reward-prizes/{id}") public Object prize(@PathVariable long id,@RequestBody Map<String,Object> body){return rewards.prize(id,body);}
    @GetMapping("/prize-redemptions") public Object redemptions(@RequestParam(required=false)Long studentId){return rewards.redemptions(studentId);}
    @PostMapping("/prize-redemptions") public Object redeem(@RequestBody Map<String,Object> body){return rewards.redeem(body);}
    @PostMapping("/prize-redemptions/{id}/photos") public Object photos(@PathVariable long id,@RequestBody Map<String,Object> body){return rewards.photos(id,body);}
}
