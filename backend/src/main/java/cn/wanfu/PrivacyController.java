package cn.wanfu;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PrivacyController {
    final PrivacyService privacy; final Security sec; final FileService files;
    public PrivacyController(PrivacyService privacy,Security sec,FileService files) { this.privacy=privacy; this.sec=sec; this.files=files; }
    @PostMapping("/auth/logout") public Object logout() { return privacy.logout(); }
    @GetMapping("/files/{id}/access") public Object access(@PathVariable long id) { return ResponseEntity.ok().header("Cache-Control","no-store").body(files.access(id)); }
    @GetMapping("/privacy/policy") public Object policy() { return privacy.policy(); }
    @GetMapping("/privacy/consents") public Object consents(@RequestParam long studentId) { return privacy.consents(studentId); }
    @PostMapping("/privacy/consents") public Object consent(@RequestBody Map<String,Object> b) { return privacy.consent(b); }
    @GetMapping("/privacy/requests") public Object requests(@RequestParam(required=false) Long studentId) { return privacy.requests(studentId); }
    @GetMapping("/privacy/requests/{id}") public Object request(@PathVariable long id) { return privacy.request(id); }
    @PostMapping("/privacy/requests") public Object create(@RequestBody Map<String,Object> b) { return privacy.createRequest(b); }
    @PostMapping("/privacy/requests/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object> b) { return privacy.review(id,b); }
    @PostMapping("/privacy/requests/{id}/export") public Object export(@PathVariable long id) { return privacy.export(id); }
    @GetMapping("/privacy/requests/{id}/erasure-check") public Object check(@PathVariable long id) { return privacy.checkErasure(id); }
    @PostMapping("/privacy/requests/{id}/erase") public Object erase(@PathVariable long id,@RequestBody Map<String,Object> b) { return privacy.erase(id,b); }
}
