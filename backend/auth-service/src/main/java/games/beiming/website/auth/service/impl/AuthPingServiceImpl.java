package games.beiming.website.auth.service.impl;

import games.beiming.website.auth.service.AuthPingService;
import games.beiming.website.auth.vo.AuthPingVO;
import org.springframework.stereotype.Service;

@Service
public class AuthPingServiceImpl implements AuthPingService {

    @Override
    public AuthPingVO ping() {
        return new AuthPingVO("auth-service", "pong");
    }
}
