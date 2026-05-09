package games.beiming.website.notification.service.impl;

import games.beiming.website.notification.service.PingService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
    @Override
    public Map<String, String> ping() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("service", "notification-service");
        data.put("message", "pong");
        return data;
    }
}
