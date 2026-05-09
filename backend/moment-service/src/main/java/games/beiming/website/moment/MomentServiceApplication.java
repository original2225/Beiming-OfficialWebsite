package games.beiming.website.moment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class MomentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MomentServiceApplication.class, args);
    }
}
