package dev.beiming.community;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class CommunityRules {
  private CommunityRules() {
  }

  static long now() {
    return Instant.now().toEpochMilli();
  }

  static int normalizePage(int page) {
    return Math.max(page, 1);
  }

  static int normalizePageSize(int pageSize) {
    if (pageSize <= 0) return 20;
    return Math.min(pageSize, 100);
  }

  static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  static String cleanRequired(String value, String message) {
    var result = clean(value);
    if (result.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, message);
    return result;
  }

  static String slug(String value) {
    var result = cleanRequired(value, "板块标识不能为空").toLowerCase();
    if (!result.matches("[a-z0-9-]{2,80}")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "板块标识格式不正确");
    }
    return result;
  }

  static BoardVisibility boardVisibility(String value) {
    return BoardVisibility.parse(value);
  }

  static PostingRole postingRole(String value) {
    return PostingRole.parse(value);
  }

  static ContentVisibility contentVisibility(String value) {
    return ContentVisibility.parse(value);
  }

  static PostStatus editablePostStatus(String value) {
    var status = PostStatus.parse(value);
    if (status == PostStatus.HIDDEN || status == PostStatus.DELETED) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "普通编辑不能直接设置该帖子状态");
    }
    return status;
  }

  static boolean canViewBoard(CurrentUserView viewer, String visibility) {
    return switch (BoardVisibility.parse(visibility)) {
      case PUBLIC -> true;
      case MEMBER_ONLY -> viewer != null;
      case ADMIN_ONLY, HIDDEN -> viewer != null && viewer.isAdmin();
    };
  }

  static boolean canPostToBoard(CurrentUserView viewer, BoardRecord board) {
    if (viewer == null) return false;
    if (!canViewBoard(viewer, board.visibility())) return false;
    var role = PostingRole.parse(board.postingRole());
    return role == PostingRole.MEMBER || viewer.isAdmin();
  }

  static boolean canViewContent(CurrentUserView viewer, String visibility) {
    return switch (ContentVisibility.parse(visibility)) {
      case PUBLIC -> true;
      case MEMBER_ONLY -> viewer != null;
      case ADMIN_ONLY -> viewer != null && viewer.isAdmin();
    };
  }

  static boolean canViewPost(CurrentUserView viewer, PostRecord post) {
    var status = PostStatus.parse(post.status());
    if (status == PostStatus.DELETED) return viewer != null && viewer.isAdmin();
    if (status == PostStatus.DRAFT) return viewer != null && (viewer.isAdmin() || viewer.id().equals(post.authorUserId()));
    if (status == PostStatus.HIDDEN) return viewer != null && viewer.isAdmin();
    if (ReviewStatus.parse(post.reviewStatus()) == ReviewStatus.REJECTED) return viewer != null && viewer.isAdmin();
    return canViewContent(viewer, post.visibility());
  }

  static boolean canViewComment(CurrentUserView viewer, CommentRecord comment) {
    var status = CommentStatus.parse(comment.status());
    if (status == CommentStatus.VISIBLE) return true;
    return viewer != null && viewer.isAdmin();
  }

  static void requireAdmin(CurrentUserView user) {
    if (user == null || !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "没有管理员权限");
  }

  static void requireAuthorOrAdmin(CurrentUserView user, String authorUserId, String message) {
    if (user == null || (!user.isAdmin() && !user.id().equals(authorUserId))) {
      throw new ApiException(HttpStatus.FORBIDDEN, message);
    }
  }

  static void validatePollRequest(CreatePollRequest request) {
    if (request == null) return;
    cleanRequired(request.question(), "投票问题不能为空");
    var options = request.options();
    if (options == null || options.size() < 2) throw new ApiException(HttpStatus.BAD_REQUEST, "投票至少需要两个选项");
    if (options.size() > 10) throw new ApiException(HttpStatus.BAD_REQUEST, "投票选项不能超过 10 个");
    options.forEach(option -> cleanRequired(option == null ? "" : option.text(), "投票选项不能为空"));
    PollVoteMode.parse(request.voteMode());
    PollResultVisibility.parse(request.resultVisibility());
  }

  static Set<String> normalizeOptionIds(List<String> optionIds) {
    if (optionIds == null || optionIds.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "至少选择一个投票选项");
    var result = new LinkedHashSet<String>();
    for (var value : optionIds) {
      var cleaned = clean(value);
      if (!cleaned.isBlank()) result.add(cleaned);
    }
    if (result.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "至少选择一个投票选项");
    return result;
  }

  static String queryTerm(String value) {
    return clean(value).toLowerCase();
  }
}
