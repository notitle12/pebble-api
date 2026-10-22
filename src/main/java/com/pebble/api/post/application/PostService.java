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
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.post.infrastructure.persistence.PostBlockRepository;
import com.pebble.api.project.application.ProjectQueryService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final MemberQueryService members;
    private final CategoryQueryService categories;
    private final BoardQueryService boards;
    private final ProjectQueryService projects;
    private final org.springframework.beans.factory.ObjectProvider<com.pebble.api.global.media.R2ObjectStorage> mediaStorage;
    private final PostThumbnailService thumbnails;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        moveToBoardStart(post, null);
        post.changeProject(input.projectId());
        appendToProject(post);
        ordered.add(position, post);
        applyOrder(ordered);
        List<PostBlock> savedBlocks = insertBlocks(post, input.blocks());
        if (input.has("thumbnailImageId")) thumbnails.select(post, input.thumbnailImageId());
        post.changeTags(input.tags());
        posts.flush();
        recordPublication(post);
        return detailView(post, savedBlocks, true);
    }

    private void recordPublication(Post post) {
        if(post.isDraft() || post.getVisibility()!=PostVisibility.PUBLIC || post.getPublishedAt()==null)return;
        jdbc.update("insert into post_publication_history(post_id,member_id,first_published_at) values (?,?,?) on conflict(post_id) do nothing",
                post.getId(),post.getAuthor().getId(),java.sql.Timestamp.from(post.getPublishedAt()));
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
        Long previousBoard=post.getBoardId();
        if (input.has("boardId")) {
            if (input.boardId() != null) boards.resolveForPost(input.boardId(), memberId);
            post.changeBoard(input.boardId());
            if(!java.util.Objects.equals(previousBoard,input.boardId())) moveToBoardStart(post,previousBoard);
        }
        if (input.has("projectId")) {
            if (input.projectId() != null) projects.resolveForPost(input.projectId(), memberId);
            Long previousProject = post.getProjectId();
            post.changeProject(input.projectId());
            if (!Objects.equals(previousProject, input.projectId())) appendToProject(post);
        }
        Category category = post.getCategory();
        if (input.has("categoryId")) {
            category = input.categoryId() == null ? null : categories.resolveForPost(input.categoryId(),
                    category == null ? null : category.getId());
        }
        post.updateContent(input.has("title") ? input.title() : post.getTitle(),
                input.has("summary") ? input.summary() : post.getSummary(), category);
        if (input.has("visibilityStatus")) post.changeVisibility(input.visibilityStatus());
        if (input.has("displayOrder")) move(post, input.displayOrder());
        if (input.has("boardDisplayOrder")) moveInBoard(post,input.boardDisplayOrder());
        List<PostBlock> updatedBlocks = input.has("blocks") ? replaceBlocks(post, input.blocks()) : null;
        if (input.has("thumbnailImageId")) thumbnails.select(post, input.thumbnailImageId());
        else if (input.has("blocks")) thumbnails.clearIfNoLongerReferenced(post);
        if (input.has("tags")) post.changeTags(input.tags());
        posts.flush();
        recordPublication(post);
        return updatedBlocks != null ? detailView(post, updatedBlocks, true) : detailView(post, true);
    }

    @Transactional
    public void delete(long postId, long memberId) {
        members.findProfileCompletedForWrite(memberId);
        Post post = ownedForUpdate(postId, memberId);
        post.delete();
        posts.flush();
        applyOrder(posts.findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(memberId, PostVisibility.DELETED));
        normalizeBoard(memberId,post.getBoardId());
    }

    public Page<PostView> listPopular(Pageable pageable) {
        var ranked = posts.findPopularIds(pageable);
        if (ranked.isEmpty()) return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, ranked.getTotalElements());
        // 순위 ID를 먼저 조회하고 관계를 일괄 로딩한다. 다시 공개 조건을 적용해 비공개 전환도 보호한다.
        var visible = posts.findAll(publicFilter(null, null, null).and((root, query, cb) -> root.get("id").in(ranked.getContent())), Pageable.unpaged());
        var byId = visible.getContent().stream().collect(java.util.stream.Collectors.toMap(Post::getId, value -> value));
        var ordered = ranked.getContent().stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return page(new org.springframework.data.domain.PageImpl<>(ordered, pageable, ranked.getTotalElements()), false);
    }

    public Page<PostView> listPublic(Long categoryId, Long authorId, Pageable pageable) {
        return page(posts.findAll(publicFilter(categoryId, authorId, null), pageable), false);
    }

    public Page<PostView> search(String term, Long categoryId, Long authorId, Pageable pageable) {
        return page(posts.findAll(publicFilter(categoryId, authorId, null).and(searchMatching(term)), pageable), false);
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
            return cb.or(cb.like(cb.lower(root.get("title")), loweredPattern, '\\'), cb.exists(body),
                    cb.like(cb.lower(category.get("name")), loweredPattern, '\\'),
                    cb.like(cb.lower(parent.get("name")), loweredPattern, '\\'));
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
        applyOrder(posts.findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(post.getAuthor().getId(), PostVisibility.DELETED));
        normalizeBoard(post.getAuthor().getId(),post.getBoardId());
    }

    @Transactional
    public Post lockForReportManagement(long id) { return forManagementUpdate(id); }

    private Post forManagementUpdate(long id) {
        long authorId = posts.findAuthorIdForManagement(id).orElseThrow(PostService::notFound);
        members.findForManagementWrite(authorId);
        return posts.findByIdForUpdate(id).orElseThrow(PostService::notFound);
    }

    public Page<PostView> listBoard(long ownerId, long boardId, Pageable pageable) {
        boards.requirePublic(ownerId, boardId);
        return page(posts.findAll(publicFilter(null, ownerId, boardId), boardPageable(pageable)), false);
    }

    public Page<PostView> listProject(long projectId, Pageable pageable) {
        long ownerId = projects.requirePublic(projectId);
        Specification<Post> placement = (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
        return page(posts.findAll(publicFilter(null, ownerId, null).and(placement), pageable), false);
    }

    public Page<PostView> listProjectForOwner(long projectId, long memberId, Pageable pageable) {
        members.findProfileCompleted(memberId);
        projects.resolveForPost(projectId, memberId);
        Specification<Post> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("author").get("id"), memberId), cb.equal(root.get("projectId"), projectId),
                cb.notEqual(root.get("visibility"), PostVisibility.DELETED));
        return page(posts.findAll(filter, pageable), true);
    }

    @Transactional
    public void moveInProject(long projectId, long postId, long memberId, int position) {
        // 연결 변경·프로젝트 삭제·다른 순서 변경과 동일한 회원 행 잠금을 사용한다.
        members.findProfileCompletedForWrite(memberId);
        projects.resolveForPost(projectId, memberId);
        List<Post> ordered = projectPosts(memberId, projectId);
        Post post = ordered.stream().filter(item -> item.getId().equals(postId)).findFirst().orElseThrow(PostService::notFound);
        if (position < 0 || position >= ordered.size()) throw new ApplicationException(
                GlobalErrorCode.VALIDATION_ERROR, "displayOrder", "프로젝트에 연결된 글의 범위 안에서 위치를 지정해 주세요.");
        ordered.remove(post);
        ordered.add(position, post);
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).changeProjectOrder(index);
    }

    private List<Post> projectPosts(long memberId, long projectId) {
        return posts.findByAuthorIdAndProjectIdAndVisibilityNotOrderByProjectDisplayOrderAscIdAsc(memberId, projectId, PostVisibility.DELETED);
    }

    private void appendToProject(Post post) {
        if (post.getProjectId() == null) { post.changeProjectOrder(0); return; }
        List<Post> ordered = projectPosts(post.getAuthor().getId(), post.getProjectId());
        ordered.removeIf(item -> item.getId().equals(post.getId()));
        // 삭제 등으로 남은 간격도 함께 정규화하고 새 연결은 마지막에 둔다.
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).changeProjectOrder(index);
        post.changeProjectOrder(ordered.size());
    }

    private Specification<Post> publicFilter(Long categoryId, Long authorId, Long boardId) {
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
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public Page<PostView> listMine(long memberId, PostVisibility visibility, Pageable pageable) { return listMine(memberId,visibility,null,pageable); }

    public Page<PostView> listMine(long memberId, PostVisibility visibility, Long boardId, Pageable pageable) {
        members.findActiveById(memberId);
        if(boardId!=null)boards.resolveForPost(boardId,memberId);
        Specification<Post> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("author").get("id"), memberId),
                cb.notEqual(root.get("visibility"), PostVisibility.DELETED),
                visibility == null ? cb.conjunction() : cb.equal(root.get("visibility"), visibility),
                boardId==null ? cb.conjunction() : cb.equal(root.get("boardId"),boardId));
        return page(posts.findAll(filter, boardId==null?pageable:boardPageable(pageable)), true);
    }

    public Page<PostView> listOwnBlog(long memberId, Long boardId, String term, String placement, Pageable pageable) {
        members.findActiveById(memberId);
        if (boardId != null) boards.resolveForPost(boardId, memberId);
        // 블로그에는 발행한 글만 표시하고 초안·차단 글은 기존 관리 화면에 남긴다.
        Specification<Post> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("author").get("id"), memberId),
                root.get("visibility").in(PostVisibility.PUBLIC, PostVisibility.HIDDEN),
                cb.isFalse(root.get("draft")), cb.isFalse(root.get("blocked")),
                boardId == null ? cb.conjunction() : cb.equal(root.get("boardId"), boardId),
                placement == null ? cb.conjunction() : cb.isNotNull(root.get("boards".equals(placement) ? "boardId" : "projectId")));
        if (term != null) filter = filter.and(searchMatching(term));
        return page(posts.findAll(filter, boardId == null ? pageable : boardPageable(pageable)), true);
    }

    public Page<PostView> listBlog(String handle, Long categoryId, Pageable pageable) {
        return listPublic(categoryId, members.findPublicBlog(handle).getId(), pageable);
    }

    public Page<PostView> listBlogPlacement(String handle, String placement, Long categoryId, Pageable pageable) {
        long memberId = members.findPublicBlog(handle).getId();
        Specification<Post> placementFilter = (root, query, cb) -> cb.isNotNull(root.get(
                "boards".equals(placement) ? "boardId" : "projectId"));
        return page(posts.findAll(publicFilter(categoryId, memberId, null).and(placementFilter), pageable), false);
    }

    public Page<PostView> searchBlog(String handle, String term, Long categoryId, Pageable pageable) {
        return search(term, categoryId, members.findPublicBlog(handle).getId(), pageable);
    }

    private Post ownedForUpdate(long postId, long memberId) {
        // 작성자 확인과 상태 전이를 같은 행 잠금 안에서 처리한다.
        Post post = posts.findByIdForUpdate(postId).orElseThrow(PostService::notFound);
        if (!post.getAuthor().getId().equals(memberId)) throw notFound();
        if (post.getVisibility() == PostVisibility.DELETED) throw new ApplicationException(PostError.CONTENT_DELETED);
        return post;
    }

    private List<PostBlock> insertBlocks(Post post, List<BlockInput> input) {
        List<PostBlock> created = new ArrayList<>(input.size());
        for (int order = 0; order < input.size(); order++) {
            BlockInput block = input.get(order);
            created.add(new PostBlock(post, block.type(), block.content(), block.language(), block.title(), order,
                    block.alignment()));
        }
        return blocks.saveAll(created);
    }

    private List<PostBlock> replaceBlocks(Post post, List<BlockInput> input) {
        // 기존 블록을 순서대로 재사용(In-Place Dirty Checking)하고 초과분 삭제·신규분 삽입만 수행한다.
        List<PostBlock> existing = blocks.findByPostIdOrderByDisplayOrderAsc(post.getId());
        int commonSize = Math.min(existing.size(), input.size());
        List<PostBlock> result = new ArrayList<>(input.size());
        for (int order = 0; order < commonSize; order++) {
            BlockInput block = input.get(order);
            PostBlock target = existing.get(order);
            target.update(block.type(), block.content(), block.language(), block.title(), order, block.alignment());
            result.add(target);
        }
        if (existing.size() > input.size()) {
            blocks.deleteAll(existing.subList(input.size(), existing.size()));
        } else if (input.size() > existing.size()) {
            List<PostBlock> additions = new ArrayList<>(input.size() - existing.size());
            for (int order = existing.size(); order < input.size(); order++) {
                BlockInput block = input.get(order);
                additions.add(new PostBlock(post, block.type(), block.content(), block.language(), block.title(), order,
                        block.alignment()));
            }
            result.addAll(blocks.saveAll(additions));
        }
        blocks.flush();
        return result;
    }

    private Pageable boardPageable(Pageable pageable) {
        return org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(),pageable.getPageSize(),
                org.springframework.data.domain.Sort.by(pageable.getSort().stream().map(order->order.getProperty().equals("displayOrder")?order.withProperty("boardDisplayOrder"):order).toList()));
    }
    private List<Post> boardPosts(long memberId,Long boardId) {
        return posts.findByAuthorIdAndBoardIdAndVisibilityNotOrderByBoardDisplayOrderAscIdAsc(memberId,boardId,PostVisibility.DELETED);
    }
    private void normalizeBoard(long memberId,Long boardId) {
        if(boardId==null)return;
        var rows=boardPosts(memberId,boardId);
        for(int index=0;index<rows.size();index++)rows.get(index).changeBoardOrder(index);
    }
    private void moveToBoardStart(Post post,Long previousBoard) {
        normalizeBoard(post.getAuthor().getId(),previousBoard);
        if(post.getBoardId()==null){post.changeBoardOrder(0);return;}
        var rows=boardPosts(post.getAuthor().getId(),post.getBoardId());
        rows.removeIf(row->row.getId().equals(post.getId()));rows.add(0,post);
        for(int index=0;index<rows.size();index++)rows.get(index).changeBoardOrder(index);
    }
    private void moveInBoard(Post post,int position) {
        if(post.getBoardId()==null)throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,"boardDisplayOrder","게시판에 속한 글만 순서를 바꿀 수 있어요.");
        var rows=boardPosts(post.getAuthor().getId(),post.getBoardId());
        if(position>=rows.size())throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,"boardDisplayOrder","게시판의 글 수 안에서 순서를 지정해 주세요.");
        rows.removeIf(row->row.getId().equals(post.getId()));rows.add(position,post);
        for(int index=0;index<rows.size();index++)rows.get(index).changeBoardOrder(index);
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
        return detailView(post, blocks.findByPostIdOrderByDisplayOrderAsc(post.getId()), owner);
    }

    private PostView detailView(Post post, List<PostBlock> postBlocks, boolean owner) {
        initializeRelations(post);
        return new PostView(post, postBlocks,
                post.getTags(), owner, 0, false, thumbnailUrl(post));
    }

    private Page<PostView> page(Page<Post> page, boolean owner) {
        return page.map(post -> new PostView(post, null, post.getTags(), owner, 0, false, thumbnailUrl(post)));
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

    public record PostView(Post post, List<PostBlock> blocks, List<String> tags, boolean owner,
                           long likeCount, boolean likedByMe, String thumbnailUrl) {
        public PostView(Post post, List<PostBlock> blocks, List<String> tags, boolean owner, long likeCount, boolean likedByMe) {
            this(post, blocks, tags, owner, likeCount, likedByMe, null);
        }
        public PostView(Post post, List<PostBlock> blocks, List<String> tags, boolean owner) {
            this(post, blocks, tags, owner, 0, false);
        }
    }
}
