package kr.gongchae.bell;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import kr.gongchae.bell.alerts.ErasureJournalTool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GongchaeBellApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals(ErasureJournalTool.COMMAND))  // operator file tool: no Spring context, no DB
            System.exit(ErasureJournalTool.run(Arrays.copyOfRange(args, 1, args.length), System.out));
        SpringApplication.run(GongchaeBellApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
