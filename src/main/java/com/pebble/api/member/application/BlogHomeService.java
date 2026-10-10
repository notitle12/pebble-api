package com.pebble.api.member.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.application.PostQueryService;
import com.pebble.api.project.application.ProjectQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogHomeService {
    public static final Set<String> SECTION_KEYS = Set.of("ACTIVITY", "TECH_STACKS", "PROJECTS", "RECENT_POSTS");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MemberQueryService members;
    private final ProjectQueryService projects;
    private final PostQueryService posts;
    private final Clock clock;

    public record Section(String key, boolean visible) { }
    public record Settings(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds) { }
    public record Activity(LocalDate from, LocalDate to, String timeZone, List<PostQueryService.ActivityDay> days) { }
    public record Home(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds, Activity activity) { }

    public Settings mine(long memberId) {
        members.findProfileCompleted(memberId);
        return read(memberId);
    }

    @Transactional
    public Settings save(long memberId, Settings settings) {
        members.findProfileCompletedForWrite(memberId);
        // 회원 잠금 뒤 프로젝트 잠금을 획득한다. 다른 회원의 프로젝트는 연결하지 않는다.
        for (String id : settings.featuredProjectIds()) {
            long projectId = Long.parseLong(id);
            projects.resolveForPost(projectId, memberId);
            projects.requirePublicForWrite(projectId);
        }
        try {
            jdbc.update("""
                    insert into blog_home_settings(member_id, settings) values (?, ?::jsonb)
                    on conflict(member_id) do update set settings=excluded.settings, updated_at=now()
                    """, memberId, mapper.writeValueAsString(settings));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("블로그 홈 구성을 저장하지 못했습니다.", exception); }
        return settings;
    }

    public Home publicHome(String handle) {
        long memberId = members.findPublicBlog(handle).getId();
        Settings settings = read(memberId);
        List<String> publicProjects = new ArrayList<>();
        for (String id : settings.featuredProjectIds()) {
            try {
                if (projects.requirePublic(Long.parseLong(id)) == memberId) publicProjects.add(id);
            } catch (ApplicationException exception) {
                if (exception.error() != GlobalErrorCode.RESOURCE_NOT_FOUND) throw exception;
            }
        }
        LocalDate to = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        LocalDate from = to.minusYears(1).plusDays(1);
        boolean activityVisible = settings.sections().stream().anyMatch(section -> section.key().equals("ACTIVITY") && section.visible());
        return new Home(settings.sections(), settings.techStacks(), List.copyOf(publicProjects),
                new Activity(from, to, "Asia/Seoul", activityVisible ? posts.publicActivity(memberId, from, to) : List.of()));
    }

    private Settings read(long memberId) {
        var values = jdbc.queryForList("select settings::text from blog_home_settings where member_id=?", String.class, memberId);
        if (values.isEmpty()) return new Settings(List.of(new Section("ACTIVITY", true), new Section("TECH_STACKS", true),
                new Section("PROJECTS", true), new Section("RECENT_POSTS", true)), List.of(), List.of());
        try { return mapper.readValue(values.getFirst(), Settings.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("블로그 홈 구성을 읽지 못했습니다.", exception); }
    }
}
