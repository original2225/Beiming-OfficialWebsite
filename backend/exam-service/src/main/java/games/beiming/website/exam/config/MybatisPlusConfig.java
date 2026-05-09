package games.beiming.website.exam.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.exam.mapper")
public class MybatisPlusConfig {
}
