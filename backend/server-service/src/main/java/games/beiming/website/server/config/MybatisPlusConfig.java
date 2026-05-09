package games.beiming.website.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.server.mapper")
public class MybatisPlusConfig {
}
