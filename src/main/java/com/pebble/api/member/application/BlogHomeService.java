package com.pebble.api.member.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public record Settings(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds, String activityDisplay, Integer featuredProjectLimit) {
        public Settings { if (activityDisplay == null) activityDisplay = "GRASS"; if (featuredProjectLimit == null) featuredProjectLimit = 6; }
        public Settings(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds, String activityDisplay) { this(sections, techStacks, featuredProjectIds, activityDisplay, 6); }
        public Settings(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds) { this(sections, techStacks, featuredProjectIds, "GRASS"); }
    }
    public record Activity(LocalDate from, LocalDate to, String timeZone, List<PostQueryService.ActivityDay> days) { }
    public record Home(List<Section> sections, List<String> techStacks, List<String> featuredProjectIds, String activityDisplay, Integer featuredProjectLimit, Activity activity) { }

    public Settings mine(long memberId) {
        members.findProfileCompleted(memberId);
        return read(memberId);
    }

    @Transactional
    public Settings save(long memberId, Settings settings) {
        members.findProfileCompletedForWrite(memberId);
        Settings previous = read(memberId);
        List<String> selected = new ArrayList<>();
        // 이미 선택됐던 프로젝트가 비공개·삭제되면 설정 저장을 막지 않고 제외한다.
        for (String id : settings.featuredProjectIds()) {
            if (previous.featuredProjectIds().contains(id) && !projects.isPublicOwnedBy(Long.parseLong(id), memberId)) continue;
            long projectId = Long.parseLong(id);
            projects.resolveForPost(projectId, memberId);
            projects.requirePublicForWrite(projectId);
            selected.add(id);
        }
        settings = new Settings(settings.sections(), settings.techStacks(), List.copyOf(selected), settings.activityDisplay(), settings.featuredProjectLimit());
        try {
            jdbc.update("""
                    insert into blog_home_settings(member_id, settings) values (?, ?::jsonb)
                    on conflict(member_id) do update set settings=excluded.settings, updated_at=now()
                    """, memberId, mapper.writeValueAsString(settings));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("블로그 홈 구성을 저장하지 못했습니다.", exception); }
        return settings;
    }

    @Transactional
    public Settings setFeatured(long memberId, long projectId, boolean featured) {
        members.findProfileCompletedForWrite(memberId);
        Settings current = read(memberId);
        List<String> ids = new ArrayList<>(current.featuredProjectIds().stream()
                .filter(id -> projects.isPublicOwnedBy(Long.parseLong(id), memberId)).toList());
        String id = Long.toString(projectId);
        if (featured) {
            projects.resolveForPost(projectId, memberId);
            projects.requirePublicForWrite(projectId);
            if (!ids.contains(id)) {
                if (ids.size() >= 6) throw new com.pebble.api.global.exception.ApplicationException(
                        com.pebble.api.global.exception.GlobalErrorCode.VALIDATION_ERROR, "featuredProjectIds", "대표 프로젝트는 최대 6개까지 지정할 수 있습니다.");
                ids.add(id);
            }
        } else ids.remove(id);
        return save(memberId, new Settings(current.sections(), current.techStacks(), List.copyOf(ids), current.activityDisplay(), current.featuredProjectLimit()));
    }

    public Home publicHome(String handle) {
        long memberId = members.findPublicBlog(handle).getId();
        Settings settings = read(memberId);
        List<String> publicProjects = new ArrayList<>();
        for (String id : settings.featuredProjectIds()) {
            if (projects.isPublicOwnedBy(Long.parseLong(id), memberId)) publicProjects.add(id);
        }
        LocalDate to = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        LocalDate from = to.minusYears(1).plusDays(1);
        boolean activityVisible = settings.sections().stream().anyMatch(section -> section.key().equals("ACTIVITY") && section.visible());
        return new Home(settings.sections(), settings.techStacks(), publicProjects.stream().limit(settings.featuredProjectLimit()).toList(), settings.activityDisplay(), settings.featuredProjectLimit(),
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
