package com.pebble.api.member.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.post.application.PostQueryService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogStoneService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MemberQueryService members;
    private final PostQueryService posts;
    public record Stone(String id, List<String> postIds, double x, double y, double rotation, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer shape) { }
    public record Layout(int version, List<Stone> stones) { }
    public record View(int version, List<Stone> stones, List<PostQueryService.StonePost> posts) { }

    public View mine(long memberId) { members.findProfileCompleted(memberId); return visible(memberId, read(memberId)); }
    public View publicView(String handle) { long memberId=members.findPublicBlog(handle).getId();return visible(memberId,read(memberId)); }
    public PostQueryService.StonePostPage inventory(long memberId, int page) { members.findProfileCompleted(memberId); return posts.stonePostPage(memberId, page); }

    @Transactional
    public View save(long memberId, Layout layout) {
        members.findProfileCompletedForWrite(memberId);
        if (read(memberId).version() != layout.version()) throw new ApplicationException(MemberError.STONE_LAYOUT_CHANGED);
        var ids = ids(layout);
        if (posts.stonePosts(memberId, ids, true).size() != ids.size()) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        try {
            jdbc.update("""
                    insert into blog_stone_layout(member_id, version, stones) values (?, ?, ?::jsonb)
                    on conflict(member_id) do update set version=excluded.version, stones=excluded.stones, updated_at=now()
                    """, memberId, layout.version() + 1, mapper.writeValueAsString(layout.stones()));
        } catch (java.io.IOException exception) { throw new IllegalStateException("돌 배치를 저장하지 못했습니다.", exception); }
        return visible(memberId, new Layout(layout.version() + 1, layout.stones()));
    }
    private View visible(long memberId, Layout layout) {
        var sources = posts.stonePosts(memberId, ids(layout), false);
        Set<String> allowed = sources.stream().map(PostQueryService.StonePost::id).collect(Collectors.toSet());
        var visible = layout.stones().stream().map(stone -> new Stone(stone.id(), stone.postIds().stream().filter(allowed::contains).toList(), stone.x(), stone.y(), stone.rotation(), stone.shape())).filter(stone -> !stone.postIds().isEmpty()).toList();
        return new View(layout.version(), visible, sources);
    }
    private List<Long> ids(Layout layout) { return layout.stones().stream().flatMap(stone -> stone.postIds().stream()).map(Long::parseLong).toList(); }
    private Layout read(long memberId) {
        var rows = jdbc.query("select version, stones::text from blog_stone_layout where member_id=?", (row, index) -> {
            try { return new Layout(row.getInt(1), mapper.readValue(row.getString(2), new TypeReference<List<Stone>>() { })); }
            catch (java.io.IOException exception) { throw new IllegalStateException("돌 배치를 읽지 못했습니다.", exception); }
        }, memberId);
        return rows.isEmpty() ? new Layout(0, List.of()) : rows.getFirst();
    }
}
