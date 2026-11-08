package com.pebble.api.post.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostError;
import com.pebble.api.post.domain.PostSeries;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.post.infrastructure.persistence.PostSeriesRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostSeriesService {
    private final PostSeriesRepository series;
    private final PostRepository posts;
    private final MemberQueryService members;
    private final JdbcTemplate jdbc;

    public List<SeriesView> mine(long memberId) {
        members.findProfileCompleted(memberId);
        return list(memberId, false);
    }

    public List<SeriesView> publicList(String handle) {
        return list(members.findPublicBlog(handle).getId(), true);
    }

    private List<SeriesView> list(long memberId, boolean publicly) {
        // 개별 시리즈마다 다시 조회하지 않고 노출 가능한 글만 한 번에 집계한다.
        String visible = publicly ? " and p.visibility_status='PUBLIC' and not p.is_draft and not p.is_blocked" : " and p.visibility_status<>'DELETED'";
        return jdbc.query("select s.*,count(p.id) as post_count from post_series s left join post p on p.series_id=s.id "
                + "and p.author_member_id=s.owner_member_id" + visible
                + " where s.owner_member_id=? group by s.id" + (publicly ? " having count(p.id)>0" : "")
                + " order by s.display_order,s.id", (rs, row) -> new SeriesView(rs.getString("id"),
                rs.getString("name"), rs.getString("description"), rs.getLong("post_count"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), memberId);
    }

    public SeriesView own(long id, long memberId) {
        members.findProfileCompleted(memberId);
        return view(owned(id, memberId), false);
    }

    public SeriesView publicly(String handle, long id) {
        PostSeries found = owned(id, members.findPublicBlog(handle).getId());
        SeriesView result = view(found, true);
        if (result.postCount() == 0) throw notFound();
        return result;
    }

    @Transactional
    public SeriesView create(long memberId, PostSeriesChanges input) {
        members.findProfileCompletedForWrite(memberId);
        var ordered = new ArrayList<>(series.findByOwnerMemberIdOrderByDisplayOrderAscIdAsc(memberId));
        if (ordered.size() >= 100) throw new ApplicationException(PostError.SERIES_LIMIT);
        PostSeries created = series.save(new PostSeries(memberId, input.name(), input.description()));
        ordered.addFirst(created);
        applySeriesOrder(ordered);
        series.flush();
        return view(created, false);
    }

    @Transactional
    public SeriesView update(long id, long memberId, PostSeriesChanges input) {
        members.findProfileCompletedForWrite(memberId);
        PostSeries found = owned(id, memberId);
        if (input.displayOrder() != null) {
            var ordered = new ArrayList<>(series.findByOwnerMemberIdOrderByDisplayOrderAscIdAsc(memberId));
            int position = input.displayOrder();
            if (position < 0 || position >= ordered.size()) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,
                    "displayOrder", "현재 시리즈 목록의 순서 범위를 확인해 주세요.");
            ordered.removeIf(item -> item.getId().equals(found.getId()));
            ordered.add(position, found);
            applySeriesOrder(ordered);
        }
        found.update(input.name() == null ? found.getName() : input.name(),
                input.description() == null ? found.getDescription() : input.description());
        series.flush();
        return view(found, false);
    }

    @Transactional
    public void delete(long id, long memberId) {
        // 글의 작성·연결 변경과 동일한 회원 잠금 아래에서 연결만 해제한다.
        members.findProfileCompletedForWrite(memberId);
        PostSeries found = owned(id, memberId);
        posts.detachSeries(memberId, id, Instant.now());
        series.delete(found);
        series.flush();
        applySeriesOrder(series.findByOwnerMemberIdOrderByDisplayOrderAscIdAsc(memberId));
        series.flush();
    }

    public void resolveForPost(long id, long memberId) { owned(id, memberId); }

    /** 호출자는 글 작성과 같은 회원 잠금을 유지한다. */
    public void assign(Post post, Long id) {
        Long previous = post.getSeriesId();
        if (Objects.equals(previous, id)) return;
        long memberId = post.getAuthor().getId();
        if (id != null) resolveForPost(id, memberId);
        post.changeSeries(id);
        post.changeSeriesOrder(0);
        posts.flush();
        normalize(memberId, previous);
        if (id != null) {
            var ordered = new ArrayList<>(ordered(memberId, id));
            ordered.removeIf(item -> item.getId().equals(post.getId()));
            ordered.add(post);
            applyOrder(ordered);
        }
    }

    public void move(Post post, int position) {
        if (post.getSeriesId() == null) throw invalidOrder();
        var ordered = new ArrayList<>(ordered(post.getAuthor().getId(), post.getSeriesId()));
        if (position >= ordered.size()) throw invalidOrder();
        ordered.removeIf(item -> item.getId().equals(post.getId()));
        ordered.add(position, post);
        applyOrder(ordered);
    }

    @Transactional
    public void saveOrders(long memberId, PostSeriesOrderChanges input) {
        members.findProfileCompletedForWrite(memberId);
        if (input.seriesOrder() == null && input.postOrder() == null) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR);
        List<PostSeries> currentSeries = null;
        List<Post> currentPosts = null;
        if (input.seriesOrder() != null) {
            currentSeries = series.findByOwnerMemberIdOrderByDisplayOrderAscIdAsc(memberId);
            validateOrder(currentSeries.stream().map(PostSeries::getId).toList(), input.seriesOrder(), 100, PostError.SERIES_ORDER_CONFLICT);
        }
        if (input.postOrder() != null) {
            if (input.seriesId() == null) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR);
            owned(input.seriesId(), memberId);
            currentPosts = ordered(memberId, input.seriesId());
            validateOrder(currentPosts.stream().map(Post::getId).toList(), input.postOrder(), 10000, PostError.POST_ORDER_CONFLICT);
        }
        // Validate both original lists under the member lock before changing either order.
        if (currentSeries != null) {
            var byId = currentSeries.stream().collect(java.util.stream.Collectors.toMap(PostSeries::getId, item -> item));
            for (int index = 0; index < input.seriesOrder().ids().size(); index++) byId.get(input.seriesOrder().ids().get(index)).changeOrder(index);
        }
        if (currentPosts != null) {
            var byId = currentPosts.stream().collect(java.util.stream.Collectors.toMap(Post::getId, item -> item));
            for (int index = 0; index < input.postOrder().ids().size(); index++) byId.get(input.postOrder().ids().get(index)).changeSeriesOrder(index);
        }
        posts.flush();
    }

    private void validateOrder(List<Long> current, PostSeriesOrderChanges.Order order, int limit, PostError conflict) {
        var expected = order.expectedIds();
        var desired = order.ids();
        var unique = new java.util.HashSet<>(expected);
        if (expected.size() > limit || unique.size() != expected.size() || desired.size() != expected.size()
                || new java.util.HashSet<>(desired).size() != desired.size() || !unique.equals(new java.util.HashSet<>(desired))) {
            throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "ids", "전체 순서를 중복이나 누락 없이 전달해 주세요.");
        }
        if (!current.equals(expected)) throw new ApplicationException(conflict);
    }

    public void normalize(long memberId, Long id) {
        if (id != null) applyOrder(ordered(memberId, id));
    }

    private List<Post> ordered(long memberId, long id) {
        return posts.findByAuthorIdAndSeriesIdAndVisibilityNotOrderBySeriesDisplayOrderAscIdAsc(memberId, id, PostVisibility.DELETED);
    }

    private void applyOrder(List<Post> ordered) {
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).changeSeriesOrder(index);
    }

    private void applySeriesOrder(List<PostSeries> ordered) {
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).changeOrder(index);
    }

    private SeriesView view(PostSeries value, boolean publicly) {
        long count = jdbc.queryForObject("select count(*) from post where series_id=? and author_member_id=? "
                + (publicly ? "and visibility_status='PUBLIC' and not is_draft and not is_blocked" : "and visibility_status<>'DELETED'"),
                Long.class, value.getId(), value.getOwnerMemberId());
        return new SeriesView(value.getId().toString(), value.getName(), value.getDescription(), count, value.getCreatedAt(), value.getUpdatedAt());
    }

    private PostSeries owned(long id, long memberId) {
        return series.findById(id).filter(value -> value.getOwnerMemberId() == memberId).orElseThrow(PostSeriesService::notFound);
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
    private static ApplicationException invalidOrder() {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "seriesDisplayOrder", "현재 시리즈의 순서 범위를 확인해 주세요.");
    }
    public record SeriesView(String id, String name, String description, long postCount, Instant createdAt, Instant updatedAt) { }
}
