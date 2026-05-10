package games.beiming.website.auth.service;

import games.beiming.website.auth.dto.LoginRequestDTO;
import games.beiming.website.auth.dto.RegisterRequestDTO;
import games.beiming.website.auth.vo.AuthTokenVO;
import games.beiming.website.auth.vo.AuthUserVO;

public interface AuthAccountService {

    AuthTokenVO register(RegisterRequestDTO request, String clientIp);

    AuthTokenVO login(LoginRequestDTO request);

    AuthUserVO me();
}
