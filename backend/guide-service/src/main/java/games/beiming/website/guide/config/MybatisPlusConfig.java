package games.beiming.website.guide.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.guide.mapper")
public class MybatisPlusConfig {
}
