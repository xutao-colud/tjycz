package cn.wanfu;

import java.time.*;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class Application {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        SpringApplication.run(Application.class, args);
    }
    @Bean Clock clock() { return Clock.system(ZONE); }
}
