package games.beiming.website.attendance.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("games.beiming.website.attendance.mapper")
public class MybatisPlusConfig {
}
