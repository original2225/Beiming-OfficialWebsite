package games.beiming.website.content.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.content.mapper")
public class MybatisPlusConfig {
}
