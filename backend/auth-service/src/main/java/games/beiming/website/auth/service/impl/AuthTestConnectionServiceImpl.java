package games.beiming.website.auth.service.impl;

import games.beiming.website.auth.service.AuthTestConnectionService;
import games.beiming.website.auth.vo.AuthTestConnectionVO;
import org.springframework.stereotype.Service;

@Service
public class AuthTestConnectionServiceImpl implements AuthTestConnectionService {

    @Override
    public AuthTestConnectionVO testConnection() {
        return new AuthTestConnectionVO("auth-service", "UP", "gateway can reach auth-service");
    }
}
