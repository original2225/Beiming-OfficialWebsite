package games.beiming.website.auth.service;

import games.beiming.website.auth.dto.CreateInviteCodeRequestDTO;
import games.beiming.website.auth.vo.InviteCodeVO;

public interface AuthInviteCodeService {

    InviteCodeVO createInviteCode(CreateInviteCodeRequestDTO request);

    InviteCodeVO disableInviteCode(Long id);
}
