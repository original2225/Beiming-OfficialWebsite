package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PollService {
  private final PollRepository polls;
  private final PostRepository posts;
  private final BoardRepository boards;
  private final AuthClient auth;

  PollService(PollRepository polls, PostRepository posts, BoardRepository boards, AuthClient auth) {
    this.polls = polls;
    this.posts = posts;
    this.boards = boards;
    this.auth = auth;
  }

  @Transactional
  public synchronized void createForPost(String postId, CreatePollRequest request) {
    CommunityRules.validatePollRequest(request);
    if (request == null) return;
    var now = CommunityRules.now();
    var pollId = "poll-" + UUID.randomUUID().toString().substring(0, 8);
    var poll = new PollRecord(
      pollId,
      postId,
      CommunityRules.cleanRequired(request.question(), "投票问题不能为空"),
      PollVoteMode.parse(request.voteMode()).name(),
      PollResultVisibility.parse(request.resultVisibility()).name(),
      request.closesAt() == null ? 0L : Math.max(0L, request.closesAt()),
      false,
      now,
      now
    );
    var options = new ArrayList<PollOptionRecord>();
    for (var i = 0; i < request.options().size(); i++) {
      var option = request.options().get(i);
      options.add(new PollOptionRecord(
        "poll-option-" + UUID.randomUUID().toString().substring(0, 8),
        pollId,
        CommunityRules.cleanRequired(option.text(), "投票选项不能为空"),
        i + 1
      ));
    }
    polls.insert(poll, options);
    posts.setHasPoll(postId, true);
  }

  PollView viewForPost(String authorization, PostRecord post) {
    var poll = polls.findByPostId(post.id()).orElse(null);
    if (poll == null) return null;
    var viewer = auth.optionalUser(authorization);
    return toView(poll, viewer);
  }

  @Transactional
  public synchronized PollView vote(String authorization, String postId, CastPollVoteRequest request) {
    var user = auth.requireUser(authorization);
    var post = posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
    if (!canViewPost(user, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    if (post.locked() && !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "帖子已锁定");
    var poll = polls.findByPostId(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "投票不存在"));
    ensureOpen(poll);
    var options = polls.options(poll.id());
    var allowed = options.stream().map(PollOptionRecord::id).collect(java.util.stream.Collectors.toSet());
    var selected = CommunityRules.normalizeOptionIds(request == null ? null : request.optionIds());
    if (!allowed.containsAll(selected)) throw new ApiException(HttpStatus.BAD_REQUEST, "投票选项不正确");
    if (PollVoteMode.parse(poll.voteMode()) == PollVoteMode.SINGLE && selected.size() != 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "单选投票只能选择一个选项");
    }
    polls.replaceVotes(poll.id(), user.id(), List.copyOf(selected));
    return toView(poll, user);
  }

  @Transactional
  public synchronized PollView retract(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    var poll = polls.findByPostId(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "投票不存在"));
    ensureOpen(poll);
    polls.removeVotes(poll.id(), user.id());
    return toView(poll, user);
  }

  private boolean canViewPost(CurrentUserView viewer, PostRecord post) {
    if (!CommunityRules.canViewPost(viewer, post)) return false;
    var board = boards.findById(post.boardId()).orElse(null);
    return board != null && CommunityRules.canViewBoard(viewer, board.visibility());
  }

  private void ensureOpen(PollRecord poll) {
    var now = CommunityRules.now();
    if (poll.closed() || (poll.closesAt() > 0 && poll.closesAt() <= now)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "投票已结束");
    }
  }

  private PollView toView(PollRecord poll, CurrentUserView viewer) {
    var optionRecords = polls.options(poll.id());
    var myOptionIds = viewer == null ? List.<String>of() : polls.userOptionIds(poll.id(), viewer.id());
    var voted = !myOptionIds.isEmpty();
    var closed = poll.closed() || (poll.closesAt() > 0 && poll.closesAt() <= CommunityRules.now());
    var resultsVisible = viewer != null && viewer.isAdmin();
    if (!resultsVisible) {
      var visibility = PollResultVisibility.parse(poll.resultVisibility());
      resultsVisible = switch (visibility) {
        case ALWAYS -> true;
        case AFTER_VOTE -> voted;
        case AFTER_CLOSE -> closed;
      };
    }
    var canShowResults = resultsVisible;
    var voteCounts = canShowResults ? polls.countVotesByOption(poll.id()) : java.util.Map.<String, Long>of();
    var options = optionRecords.stream()
      .map(option -> new PollOptionView(
        option.id(),
        option.optionText(),
        option.sortOrder(),
        voteCounts.getOrDefault(option.id(), 0L)
      ))
      .toList();
    return new PollView(
      poll.id(),
      poll.question(),
      poll.voteMode(),
      poll.resultVisibility(),
      poll.closesAt(),
      closed,
      voted,
      resultsVisible,
      resultsVisible ? polls.totalVotes(poll.id()) : 0L,
      myOptionIds,
      options
    );
  }
}
