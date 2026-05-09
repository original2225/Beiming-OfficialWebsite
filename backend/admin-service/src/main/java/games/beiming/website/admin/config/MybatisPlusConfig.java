package games.beiming.website.admin.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.admin.mapper")
public class MybatisPlusConfig {
}
