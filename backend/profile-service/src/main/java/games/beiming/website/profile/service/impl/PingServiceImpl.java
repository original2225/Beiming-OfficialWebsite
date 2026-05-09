package games.beiming.website.profile.service.impl;

import games.beiming.website.profile.service.PingService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PingServiceImpl implements PingService {
    @Override
    public Map<String, String> ping() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("service", "profile-service");
        data.put("message", "pong");
        return data;
    }
}
