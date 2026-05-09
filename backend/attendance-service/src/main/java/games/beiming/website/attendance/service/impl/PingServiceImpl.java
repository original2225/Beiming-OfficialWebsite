package games.beiming.website.attendance.service.impl;

import games.beiming.website.attendance.service.PingService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
    @Override
    public Map<String, String> ping() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("service", "attendance-service");
        data.put("message", "pong");
        return data;
    }
}
