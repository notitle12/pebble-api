package com.pebble.api.project.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.project.application.ProjectChanges.FeatureInput;
import com.pebble.api.project.application.ProjectChanges.LinkInput;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectError;
import com.pebble.api.project.domain.ProjectFeature;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectLink;
import com.pebble.api.project.domain.ProjectTag;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectFeatureRepository;
import com.pebble.api.project.infrastructure.persistence.ProjectLinkRepository;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.project.infrastructure.persistence.ProjectTagRepository;
import com.pebble.api.tag.application.TagQueryService;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.post.application.PostProjectService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository projects;
    private final ProjectFeatureRepository features;
    private final ProjectLinkRepository links;
    private final ProjectTagRepository projectTags;
    private final MemberQueryService members;
    private final TagQueryService tags;
    private final EntityManager entityManager;
    private final PostProjectService postProjects;

    @Transactional
    public ProjectView create(long memberId, ProjectChanges input) {
        Member owner = members.findProfileCompletedForWrite(memberId);
        List<Tag> selected = tags.resolveForContent(input.tagIds(), Set.of());
        Project project = projects.saveAndFlush(new Project(owner, input.name(), input.summary(), input.description(),
                input.architectureDescription(), input.executionInstructions(), input.lifecycleStatus(),
                input.startedOn(), input.completedOn(), input.visibilityStatus()));
        replaceFeatures(project, input.features());
        replaceLinks(project, input.links());
        replaceTags(project, selected, List.of());
        projects.flush();
        return detailView(project, true);
    }

    public ProjectView detail(long projectId, Long requesterId) {
        Project project = projects.findById(projectId).orElseThrow(ProjectService::notFound);
        if (project.getVisibility() == ProjectVisibility.DELETED) throw notFound();
        boolean owner = Objects.equals(project.getOwner().getId(), requesterId);
        if (owner) members.findActiveById(requesterId);
        if (!owner && !isPublic(project)) throw notFound();
        return detailView(project, owner);
    }

    public Page<ProjectView> listPublic(Long tagId, ProjectLifecycleStatus lifecycleStatus, Pageable pageable) {
        return page(projects.findAll(publicFilter(tagId, lifecycleStatus, null), pageable), false);
    }

    public Page<ProjectView> listMember(long memberId, Long tagId, ProjectLifecycleStatus lifecycleStatus, Pageable pageable) {
        members.findPublicById(memberId);
        return page(projects.findAll(publicFilter(tagId, lifecycleStatus, memberId), pageable), false);
    }

    public Page<ProjectView> listMine(long memberId, ProjectVisibility visibility, Pageable pageable) {
        members.findActiveById(memberId);
        Specification<Project> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("owner").get("id"), memberId),
                cb.notEqual(root.get("visibility"), ProjectVisibility.DELETED),
                visibility == null ? cb.conjunction() : cb.equal(root.get("visibility"), visibility));
        return page(projects.findAll(filter, pageable), true);
    }

    public Page<ProjectView> search(String term, Long tagId, ProjectLifecycleStatus lifecycleStatus, Pageable pageable) {
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Specification<Project> matching = (root, query, cb) -> {
            var lowered = cb.lower(cb.literal(pattern));
            var technology = query.subquery(Long.class);
            var link = technology.from(ProjectTag.class);
            technology.select(link.get("project").get("id")).where(cb.equal(link.get("project").get("id"), root.get("id")),
                    cb.like(cb.lower(link.get("tag").get("name")), lowered, '\\'));
            // EXISTS로 여러 기술 Tag가 일치해도 목록과 집계가 중복되지 않게 한다.
            return cb.or(cb.like(cb.lower(root.get("name")), lowered, '\\'),
                    cb.like(cb.lower(root.get("summary")), lowered, '\\'),
                    cb.like(cb.lower(root.get("description")), lowered, '\\'), cb.exists(technology));
        };
        return page(projects.findAll(publicFilter(tagId, lifecycleStatus, null).and(matching), pageable), false);
    }

    private Specification<Project> publicFilter(Long tagId, ProjectLifecycleStatus lifecycleStatus, Long ownerId) {
        Specification<Project> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("visibility"), ProjectVisibility.PUBLIC));
            predicates.add(cb.isFalse(root.get("blocked")));
            predicates.add(cb.notEqual(root.get("owner").get("status"), MemberStatus.WITHDRAWAL_PENDING));
            if (lifecycleStatus != null) predicates.add(cb.equal(root.get("lifecycleStatus"), lifecycleStatus));
            if (ownerId != null) predicates.add(cb.equal(root.get("owner").get("id"), ownerId));
            if (tagId != null) {
                var subquery = query.subquery(Long.class);
                var link = subquery.from(ProjectTag.class);
                subquery.select(link.get("project").get("id")).where(
                        cb.equal(link.get("project").get("id"), root.get("id")),
                        cb.equal(link.get("tag").get("id"), tagId));
                predicates.add(cb.exists(subquery));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return filter;
    }

    @Transactional
    public ProjectView update(long projectId, long memberId, ProjectChanges input) {
        members.findProfileCompletedForWrite(memberId);
        Project project = ownedForUpdate(projectId, memberId);
        List<ProjectTag> currentTags = projectTags.findForProjects(List.of(projectId));
        List<Tag> selected = input.has("tagIds") ? tags.resolveForContent(input.tagIds(), currentTags.stream()
                .map(link -> link.getTag().getId()).collect(Collectors.toSet())) : null;
        project.updateContent(input.has("name") ? input.name() : project.getName(),
                input.has("summary") ? input.summary() : project.getSummary(),
                input.has("description") ? input.description() : project.getDescription(),
                input.has("architectureDescription") ? input.architectureDescription() : project.getArchitectureDescription(),
                input.has("executionInstructions") ? input.executionInstructions() : project.getExecutionInstructions(),
                input.has("lifecycleStatus") ? input.lifecycleStatus() : project.getLifecycleStatus(),
                input.has("startedOn") ? input.startedOn() : project.getStartedOn(),
                input.has("completedOn") ? input.completedOn() : project.getCompletedOn());
        if (input.has("visibilityStatus")) project.changeVisibility(input.visibilityStatus());
        if (input.has("features")) replaceFeatures(project, input.features());
        if (input.has("links")) replaceLinks(project, input.links());
        if (selected != null) replaceTags(project, selected, currentTags);
        projects.flush();
        return detailView(project, true);
    }

    @Transactional
    public void delete(long projectId, long memberId) {
        members.findProfileCompletedForWrite(memberId);
        Project project = ownedForUpdate(projectId, memberId);
        postProjects.detach(memberId, projectId);
        project.delete();
        projects.flush();
    }

    private Project ownedForUpdate(long projectId, long memberId) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(ProjectService::notFound);
        if (!project.getOwner().getId().equals(memberId)) throw notFound();
        if (project.getVisibility() == ProjectVisibility.DELETED) throw new ApplicationException(ProjectError.CONTENT_DELETED);
        return project;
    }

    private void replaceFeatures(Project project, List<FeatureInput> input) {
        features.deleteForProject(project.getId());
        features.flush();
        List<ProjectFeature> replacement = new ArrayList<>();
        for (int order = 0; order < input.size(); order++) {
            FeatureInput feature = input.get(order);
            replacement.add(new ProjectFeature(project, feature.title(), feature.description(), order));
        }
        features.saveAll(replacement);
    }

    private void replaceLinks(Project project, List<LinkInput> input) {
        links.deleteForProject(project.getId());
        links.flush();
        links.saveAll(input.stream().map(link -> new ProjectLink(project, link.type(), link.label(), link.url(),
                link.displayOrder())).toList());
    }

    private void replaceTags(Project project, List<Tag> input, List<ProjectTag> current) {
        projectTags.deleteForProject(project.getId());
        projectTags.flush();
        // 벌크 삭제는 영속성 컨텍스트를 갱신하지 않으므로 같은 복합 ID를 다시 저장하기 전에 분리한다.
        current.forEach(entityManager::detach);
        List<ProjectTag> replacement = new ArrayList<>();
        for (int order = 0; order < input.size(); order++) replacement.add(new ProjectTag(project, input.get(order), order));
        projectTags.saveAll(replacement);
    }

    private ProjectView detailView(Project project, boolean owner) {
        initializeOwner(project);
        return new ProjectView(project, features.findByProjectIdOrderByDisplayOrderAscIdAsc(project.getId()),
                links.findByProjectIdOrderByDisplayOrderAscIdAsc(project.getId()),
                projectTags.findForProjects(List.of(project.getId())).stream().map(ProjectTag::getTag).toList(), true, owner);
    }

    private Page<ProjectView> page(Page<Project> page, boolean owner) {
        Map<Long, List<Tag>> byProject = new HashMap<>();
        if (!page.isEmpty()) {
            for (ProjectTag link : projectTags.findForProjects(page.getContent().stream().map(Project::getId).toList())) {
                byProject.computeIfAbsent(link.getProject().getId(), id -> new ArrayList<>()).add(link.getTag());
            }
        }
        return page.map(project -> {
            initializeOwner(project);
            return new ProjectView(project, null, null,
                    List.copyOf(byProject.getOrDefault(project.getId(), List.of())), false, owner);
        });
    }

    private void initializeOwner(Project project) { project.getOwner().getNickname(); }
    private boolean isPublic(Project project) {
        return project.getVisibility() == ProjectVisibility.PUBLIC && !project.isBlocked()
                && project.getOwner().getStatus() != MemberStatus.WITHDRAWAL_PENDING;
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }

    public record ProjectView(Project project, List<ProjectFeature> features, List<ProjectLink> links, List<Tag> tags,
                              boolean detail, boolean owner) { }
}
