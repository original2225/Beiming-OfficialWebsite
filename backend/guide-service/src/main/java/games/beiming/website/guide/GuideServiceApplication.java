package games.beiming.website.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class GuideServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GuideServiceApplication.class, args);
    }
}
