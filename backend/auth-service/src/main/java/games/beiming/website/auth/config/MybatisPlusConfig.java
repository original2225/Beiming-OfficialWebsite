package games.beiming.website.auth.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.auth.mapper")
public class MybatisPlusConfig {
}
