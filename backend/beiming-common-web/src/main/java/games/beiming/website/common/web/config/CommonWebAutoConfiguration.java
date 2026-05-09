package games.beiming.website.common.web.config;

import games.beiming.website.common.web.controller.CommonHealthController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonWebAutoConfiguration {

    @Bean
    public CommonHealthController commonHealthController() {
        return new CommonHealthController();
    }
}
