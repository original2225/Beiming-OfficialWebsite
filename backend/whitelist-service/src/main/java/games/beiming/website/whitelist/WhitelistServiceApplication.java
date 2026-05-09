package games.beiming.website.whitelist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class WhitelistServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WhitelistServiceApplication.class, args);
    }
}
