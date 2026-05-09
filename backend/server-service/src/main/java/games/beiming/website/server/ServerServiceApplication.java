package games.beiming.website.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class ServerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerServiceApplication.class, args);
    }
}
