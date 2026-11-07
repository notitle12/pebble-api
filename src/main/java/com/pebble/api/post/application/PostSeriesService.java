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
                + " order by s.created_at desc,s.id desc", (rs, row) -> new SeriesView(rs.getString("id"),
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
        if (series.countByOwnerMemberId(memberId) >= 100) throw new ApplicationException(PostError.SERIES_LIMIT);
        return view(series.saveAndFlush(new PostSeries(memberId, input.name(), input.description())), false);
    }

    @Transactional
    public SeriesView update(long id, long memberId, PostSeriesChanges input) {
        members.findProfileCompletedForWrite(memberId);
        PostSeries found = owned(id, memberId);
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

    public void normalize(long memberId, Long id) {
        if (id != null) applyOrder(ordered(memberId, id));
    }

    private List<Post> ordered(long memberId, long id) {
        return posts.findByAuthorIdAndSeriesIdAndVisibilityNotOrderBySeriesDisplayOrderAscIdAsc(memberId, id, PostVisibility.DELETED);
    }

    private void applyOrder(List<Post> ordered) {
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).changeSeriesOrder(index);
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
