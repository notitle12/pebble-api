package com.pebble.api.post.application;

import com.pebble.api.category.application.CategoryQueryService;
import com.pebble.api.board.application.BoardQueryService;
import com.pebble.api.category.domain.Category;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.application.PostChanges.BlockInput;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostBlock;
import com.pebble.api.post.domain.PostError;
import com.pebble.api.post.domain.PostTag;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.post.infrastructure.persistence.PostBlockRepository;
import com.pebble.api.post.infrastructure.persistence.PostTagRepository;
import com.pebble.api.tag.application.TagQueryService;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.project.application.ProjectQueryService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;
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
public class PostService {
    private final PostRepository posts;
    private final PostBlockRepository blocks;
    private final PostTagRepository postTags;
    private final MemberQueryService members;
    private final CategoryQueryService categories;
    private final TagQueryService tags;
    private final BoardQueryService boards;
    private final ProjectQueryService projects;
    private final org.springframework.beans.factory.ObjectProvider<com.pebble.api.global.media.R2ObjectStorage> mediaStorage;

    @Transactional
    public void purgeForMember(long memberId) {
        // 탈퇴 파기 유스케이스가 회원 행을 잠근 뒤 호출하며 하위 이력은 FK CASCADE로 제거한다.
        posts.deleteForMember(memberId);
    }

    @Transactional
    public PostView create(long memberId, PostChanges input) {
        Member author = members.findProfileCompletedForWrite(memberId);
        if (input.boardId() != null) boards.resolveForPost(input.boardId(), memberId);
        if (input.projectId() != null) projects.resolveForPost(input.projectId(), memberId);
        Category category = input.categoryId() == null ? null : categories.resolveForPost(input.categoryId(), null);
        List<Tag> selected = tags.resolveForPost(input.tagIds(), Set.of());
        List<Post> ordered = posts.findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(memberId, PostVisibility.DELETED);
        int position = input.displayOrder() == null ? 0 : input.displayOrder();
        if (position > ordered.size()) throw invalidOrder();
        long lastNumber = posts.findLastNumber(memberId);
        if (lastNumber == Long.MAX_VALUE) throw invalidOrder();
        if (Boolean.TRUE.equals(input.draft()) && input.visibilityStatus() != PostVisibility.HIDDEN) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        String slug = allocateSlug(memberId, input.slug());
        Post post = posts.saveAndFlush(new Post(author, category, input.title(), input.summary(), input.visibilityStatus(), slug, lastNumber + 1));
        post.initializeDraft(Boolean.TRUE.equals(input.draft()));
        post.changeBoard(input.boardId());
        post.changeProject(input.projectId());
        ordered.add(position, post);
        applyOrder(ordered);
        replaceBlocks(post, input.blocks());
        postTags.saveAll(selected.stream().map(tag -> new PostTag(post, tag)).toList());
        posts.flush();
        return detailView(post, true);
    }

    public PostView detail(long postId, Long requesterId) {
        Post post = posts.findById(postId).orElseThrow(PostService::notFound);
        return visibleDetail(post, requesterId);
    }

    public PostView detailBySlug(String handle, String slug, Long requesterId) {
        return visibleDetail(posts.findByAuthorHandleAndSlug(handle, slug).orElseThrow(PostService::notFound), requesterId);
    }

    public PostView detailByNumber(String handle, long number, Long requesterId) {
        return visibleDetail(posts.findByAuthorHandleAndPostNumber(handle, number).orElseThrow(PostService::notFound), requesterId);
    }

    private String allocateSlug(long authorId, String base) {
        if (base == null) return null;
        String candidate = base;
        // 같은 작성자의 생성은 회원 행 잠금으로 직렬화하고 삭제된 slug도 재사용하지 않는다.
        for (long suffix = 2; posts.existsByAuthorIdAndSlug(authorId, candidate); suffix++) {
            String ending = "-" + suffix;
            String prefix = base.substring(0, Math.min(base.length(), 200 - ending.length()));
            candidate = prefix.replaceAll("-+$", "") + ending;
        }
        return candidate;
    }

    private PostView visibleDetail(Post post, Long requesterId) {
        if (post.getVisibility() == PostVisibility.DELETED) throw notFound();
        boolean owner = Objects.equals(post.getAuthor().getId(), requesterId);
        if (owner) members.findActiveById(requesterId);
        if (!owner && !isPublic(post)) throw notFound();
        return detailView(post, owner);
    }

    @Transactional
    public PostView update(long postId, long memberId, PostChanges input) {
        members.findProfileCompletedForWrite(memberId);
        // 주소 확정도 새 글 생성과 같은 회원 잠금 순서로 직렬화한다.
        Post post = ownedForUpdate(postId, memberId);
        if (!post.isDraft() && (input.has("slug") || Boolean.TRUE.equals(input.draft()))) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        if (post.isDraft()) {
            boolean finalize = Boolean.FALSE.equals(input.draft());
            PostVisibility nextVisibility = input.has("visibilityStatus") ? input.visibilityStatus() : post.getVisibility();
            if (!finalize && (input.has("slug") || nextVisibility != PostVisibility.HIDDEN)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
            if (finalize) {
                String address = input.has("slug") ? input.slug() : post.getSlug();
                if (!java.util.Objects.equals(address, post.getSlug())) address = allocateSlug(memberId, address);
                post.finalizeDraft(address);
            }
        }
        if (input.has("boardId")) {
            if (input.boardId() != null) boards.resolveForPost(input.boardId(), memberId);
            post.changeBoard(input.boardId());
        }
        if (input.has("projectId")) {
            if (input.projectId() != null) projects.resolveForPost(input.projectId(), memberId);
            post.changeProject(input.projectId());
        }
        Category category = post.getCategory();
        if (input.has("categoryId")) {
            category = input.categoryId() == null ? null : categories.resolveForPost(input.categoryId(),
                    category == null ? null : category.getId());
        }
        List<PostTag> currentTags = postTags.findForPosts(List.of(postId));
        List<Tag> selected = input.has("tagIds") ? tags.resolveForPost(input.tagIds(), currentTags.stream()
                .map(link -> link.getTag().getId()).collect(Collectors.toSet())) : null;
        post.updateContent(input.has("title") ? input.title() : post.getTitle(),
                input.has("summary") ? input.summary() : post.getSummary(), category);
        if (input.has("visibilityStatus")) post.changeVisibility(input.visibilityStatus());
        if (input.has("displayOrder")) move(post, input.displayOrder());
        if (input.has("blocks")) replaceBlocks(post, input.blocks());
        if (selected != null) {
            Set<Long> newIds = selected.stream().map(Tag::getId).collect(Collectors.toSet());
            Set<Long> oldIds = currentTags.stream().map(link -> link.getTag().getId()).collect(Collectors.toSet());
            postTags.deleteAll(currentTags.stream().filter(link -> !newIds.contains(link.getTag().getId())).toList());
            postTags.saveAll(selected.stream().filter(tag -> !oldIds.contains(tag.getId()))
                    .map(tag -> new PostTag(post, tag)).toList());
        }
        posts.flush();
        return detailView(post, true);
    }

    @Transactional
    public void delete(long postId, long memberId) {
        members.findProfileCompletedForWrite(memberId);
        Post post = ownedForUpdate(postId, memberId);
        post.delete();
        posts.flush();
        applyOrder(posts.findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(memberId, PostVisibility.DELETED));
    }

    public Page<PostView> listPublic(Long categoryId, Long tagId, Long authorId, Pageable pageable) {
        return page(posts.findAll(publicFilter(categoryId, tagId, authorId, null), pageable), false);
    }

    public Page<PostView> search(String term, Long categoryId, Long tagId, Long authorId, Pageable pageable) {
        return page(posts.findAll(publicFilter(categoryId, tagId, authorId, null).and(searchMatching(term)), pageable), false);
    }

    private Specification<Post> searchMatching(String term) {
        // LIKE의 특수 문자를 이스케이프해 사용자가 입력한 부분 문자열만 검색한다.
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> {
            var loweredPattern = cb.lower(cb.literal(pattern));
            var category = root.join("category", JoinType.LEFT);
            var parent = category.join("parent", JoinType.LEFT);
            var body = query.subquery(Long.class);
            var block = body.from(PostBlock.class);
            body.select(block.get("post").get("id")).where(cb.equal(block.get("post").get("id"), root.get("id")),
                    cb.or(cb.like(cb.lower(block.get("content")), loweredPattern, '\\'),
                            cb.like(cb.lower(block.get("title")), loweredPattern, '\\')));
            var technology = query.subquery(Long.class);
            var link = technology.from(PostTag.class);
            technology.select(link.get("post").get("id")).where(cb.equal(link.get("post").get("id"), root.get("id")),
                    cb.like(cb.lower(link.get("tag").get("name")), loweredPattern, '\\'));
            // 본문과 Tag는 EXISTS로 검색해 여러 일치 항목이 있어도 글과 집계를 중복하지 않는다.
            return cb.or(cb.like(cb.lower(root.get("title")), loweredPattern, '\\'), cb.exists(body),
                    cb.like(cb.lower(category.get("name")), loweredPattern, '\\'),
                    cb.like(cb.lower(parent.get("name")), loweredPattern, '\\'), cb.exists(technology));
        };
    }

    public Page<PostView> listForManagement(String q, PostVisibility visibility, Boolean blocked, Long authorId, Pageable pageable) {
        Specification<Post> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (visibility != null) predicates.add(cb.equal(root.get("visibility"), visibility));
            if (blocked != null) predicates.add(cb.equal(root.get("blocked"), blocked));
            if (authorId != null) predicates.add(cb.equal(root.get("author").get("id"), authorId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        if (q != null) filter = filter.and(searchMatching(q));
        return page(posts.findAll(filter, pageable), true);
    }

    public PostView detailForManagement(long id) {
        return detailView(posts.findById(id).orElseThrow(PostService::notFound), true);
    }

    @Transactional
    public PostView setBlockedForManagement(long id, long adminId, boolean blocked) {
        Post post = forManagementUpdate(id);
        if (post.getVisibility() == PostVisibility.DELETED) throw new ApplicationException(PostError.CONTENT_DELETED);
        post.setBlocked(blocked, adminId);
        posts.flush();
        return detailView(post, true);
    }

    @Transactional
    public void deleteForManagement(long id) {
        Post post = forManagementUpdate(id);
        if (post.getVisibility() == PostVisibility.DELETED) return;
        post.delete();
        posts.flush();
    }

    private Post forManagementUpdate(long id) {
        long authorId = posts.findAuthorIdForManagement(id).orElseThrow(PostService::notFound);
        members.findForManagementWrite(authorId);
        return posts.findByIdForUpdate(id).orElseThrow(PostService::notFound);
    }

    public Page<PostView> listBoard(long ownerId, long boardId, Pageable pageable) {
        boards.requirePublic(ownerId, boardId);
        return page(posts.findAll(publicFilter(null, null, ownerId, boardId), pageable), false);
    }

    public Page<PostView> listProject(long projectId, Pageable pageable) {
        long ownerId = projects.requirePublic(projectId);
        Specification<Post> placement = (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
        return page(posts.findAll(publicFilter(null, null, ownerId, null).and(placement), pageable), false);
    }

    private Specification<Post> publicFilter(Long categoryId, Long tagId, Long authorId, Long boardId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("visibility"), PostVisibility.PUBLIC));
            predicates.add(cb.isFalse(root.get("blocked")));
            predicates.add(cb.notEqual(root.get("author").get("status"), MemberStatus.WITHDRAWAL_PENDING));
            if (categoryId != null) {
                var category = root.join("category", JoinType.LEFT);
                var parent = category.join("parent", JoinType.LEFT);
                predicates.add(cb.or(cb.equal(category.get("id"), categoryId), cb.equal(parent.get("id"), categoryId)));
            }
            if (boardId != null) predicates.add(cb.equal(root.get("boardId"), boardId));
            if (authorId != null) predicates.add(cb.equal(root.get("author").get("id"), authorId));
            if (tagId != null) {
                var subquery = query.subquery(Long.class);
                var link = subquery.from(PostTag.class);
                subquery.select(link.get("post").get("id")).where(cb.equal(link.get("post").get("id"), root.get("id")),
                        cb.equal(link.get("tag").get("id"), tagId));
                predicates.add(cb.exists(subquery));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public Page<PostView> listMine(long memberId, PostVisibility visibility, Pageable pageable) {
        members.findActiveById(memberId);
        Specification<Post> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("author").get("id"), memberId),
                cb.notEqual(root.get("visibility"), PostVisibility.DELETED),
                visibility == null ? cb.conjunction() : cb.equal(root.get("visibility"), visibility));
        return page(posts.findAll(filter, pageable), true);
    }

    public Page<PostView> listBlog(String handle, Long categoryId, Long tagId, Pageable pageable) {
        return listPublic(categoryId, tagId, members.findPublicBlog(handle).getId(), pageable);
    }

    public Page<PostView> searchBlog(String handle, String term, Long categoryId, Long tagId, Pageable pageable) {
        return search(term, categoryId, tagId, members.findPublicBlog(handle).getId(), pageable);
    }

    private Post ownedForUpdate(long postId, long memberId) {
        // 작성자 확인과 상태 전이를 같은 행 잠금 안에서 처리한다.
        Post post = posts.findByIdForUpdate(postId).orElseThrow(PostService::notFound);
        if (!post.getAuthor().getId().equals(memberId)) throw notFound();
        if (post.getVisibility() == PostVisibility.DELETED) throw new ApplicationException(PostError.CONTENT_DELETED);
        return post;
    }

    private void replaceBlocks(Post post, List<BlockInput> input) {
        // 기존 순서 고유 제약과 충돌하지 않도록 삭제를 먼저 DB에 반영한다.
        blocks.deleteForPost(post.getId());
        List<PostBlock> replacement = new ArrayList<>();
        for (int order = 0; order < input.size(); order++) {
            BlockInput block = input.get(order);
            replacement.add(new PostBlock(post, block.type(), block.content(), block.language(), block.title(), order,
                    block.alignment()));
        }
        blocks.saveAll(replacement);
    }

    private void move(Post post, int position) {
        List<Post> ordered = posts.findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(post.getAuthor().getId(), PostVisibility.DELETED);
        if (position >= ordered.size()) throw invalidOrder();
        ordered.removeIf(item -> item.getId().equals(post.getId()));
        ordered.add(position, post);
        applyOrder(ordered);
    }

    private void applyOrder(List<Post> ordered) {
        for (int position = 0; position < ordered.size(); position++) ordered.get(position).changeOrder(position);
    }

    private ApplicationException invalidOrder() {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "displayOrder", "전체 본인 글 목록 안의 위치를 지정해 주세요.");
    }

    private PostView detailView(Post post, boolean owner) {
        initializeRelations(post);
        return new PostView(post, blocks.findByPostIdOrderByDisplayOrderAsc(post.getId()),
                postTags.findForPosts(List.of(post.getId())).stream().map(PostTag::getTag).toList(), owner, 0, false, thumbnailUrl(post));
    }

    private Page<PostView> page(Page<Post> page, boolean owner) {
        Map<Long, List<Tag>> byPost = new HashMap<>();
        if (!page.isEmpty()) {
            for (PostTag link : postTags.findForPosts(page.getContent().stream().map(Post::getId).toList())) {
                byPost.computeIfAbsent(link.getPost().getId(), id -> new ArrayList<>()).add(link.getTag());
            }
        }
        return page.map(post -> new PostView(post, null, List.copyOf(byPost.getOrDefault(post.getId(), List.of())), owner, 0, false, thumbnailUrl(post)));
    }

    private void initializeRelations(Post post) {
        // OSIV 없이도 응답 변환에 필요한 관계를 트랜잭션 안에서 읽는다.
        post.getAuthor().getNickname();
        if (post.getCategory() != null) post.getCategory().getName();
    }

    private String thumbnailUrl(Post post) {
        var client = mediaStorage.getIfAvailable();
        return client != null && post.getThumbnailStorageKey() != null && isPublic(post)
                ? client.signedUrl(post.getThumbnailStorageKey()) : null;
    }

    private boolean isPublic(Post post) {
        return post.getVisibility() == PostVisibility.PUBLIC && !post.isBlocked()
                && post.getAuthor().getStatus() != MemberStatus.WITHDRAWAL_PENDING;
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }

    public record PostView(Post post, List<PostBlock> blocks, List<Tag> tags, boolean owner,
                           long likeCount, boolean likedByMe, String thumbnailUrl) {
        public PostView(Post post, List<PostBlock> blocks, List<Tag> tags, boolean owner, long likeCount, boolean likedByMe) {
            this(post, blocks, tags, owner, likeCount, likedByMe, null);
        }
        public PostView(Post post, List<PostBlock> blocks, List<Tag> tags, boolean owner) {
            this(post, blocks, tags, owner, 0, false);
        }
    }
}
