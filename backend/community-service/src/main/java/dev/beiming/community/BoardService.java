package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class BoardService {
  private final BoardRepository boards;
  private final AuthClient auth;

  BoardService(BoardRepository boards, AuthClient auth) {
    this.boards = boards;
    this.auth = auth;
  }

  List<BoardView> publicBoards(String authorization) {
    var viewer = auth.optionalUser(authorization);
    return boards.all().stream()
      .filter(board -> CommunityRules.canViewBoard(viewer, board.visibility()))
      .map(BoardView::fromRecord)
      .toList();
  }

  synchronized BoardView create(String authorization, CreateBoardRequest request) {
    var actor = auth.requireUser(authorization);
    CommunityRules.requireAdmin(actor);
    var slug = CommunityRules.slug(request.slug());
    if (boards.findBySlug(slug).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "板块标识已经存在");
    var now = CommunityRules.now();
    var record = new BoardRecord(
      "board-" + UUID.randomUUID().toString().substring(0, 8),
      slug,
      CommunityRules.cleanRequired(request.name(), "板块名称不能为空"),
      CommunityRules.clean(request.description()),
      CommunityRules.boardVisibility(request.visibility()).name(),
      CommunityRules.postingRole(request.postingRole()).name(),
      request.sortOrder() == null ? 100 : request.sortOrder(),
      now,
      now
    );
    boards.insert(record);
    return BoardView.fromRecord(record);
  }

  synchronized BoardView update(String authorization, String boardId, UpdateBoardRequest request) {
    var actor = auth.requireUser(authorization);
    CommunityRules.requireAdmin(actor);
    var current = boards.findById(boardId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "板块不存在"));
    var next = new BoardRecord(
      current.id(),
      current.slug(),
      request.name() == null ? current.name() : CommunityRules.cleanRequired(request.name(), "板块名称不能为空"),
      request.description() == null ? current.description() : CommunityRules.clean(request.description()),
      request.visibility() == null ? current.visibility() : CommunityRules.boardVisibility(request.visibility()).name(),
      request.postingRole() == null ? current.postingRole() : CommunityRules.postingRole(request.postingRole()).name(),
      request.sortOrder() == null ? current.sortOrder() : request.sortOrder(),
      current.createdAt(),
      CommunityRules.now()
    );
    boards.update(next);
    return BoardView.fromRecord(next);
  }
}
