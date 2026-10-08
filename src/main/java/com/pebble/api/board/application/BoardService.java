package com.pebble.api.board.application;

import com.pebble.api.board.domain.Board;
import com.pebble.api.board.domain.BoardError;
import com.pebble.api.board.infrastructure.persistence.BoardRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.application.PostBoardService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BoardService {
    private final BoardRepository boards;
    private final MemberQueryService members;
    private final PostBoardService postBoards;

    public void purgeForMember(long memberId) {
        // 미삭제·논리 삭제 Board를 모두 잎부터 제거해 자기 참조 FK를 보존한다.
        for (int depth = 0; depth < 3; depth++) boards.deleteLeavesForMember(memberId);
        if (boards.existsByOwnerMemberId(memberId)) throw new ApplicationException(GlobalErrorCode.INTERNAL_ERROR);
    }

    public Board create(long memberId, BoardChanges input) {
        members.findActiveForWrite(memberId);
        List<Board> tree = tree(memberId);
        validate(tree, null, input.name(), input.parentId());
        return boards.saveAndFlush(new Board(memberId, input.name(), input.parentId(),
                input.displayOrder() == null ? 0 : input.displayOrder()));
    }

    public Board update(long boardId, long memberId, BoardChanges input) {
        members.findActiveForWrite(memberId);
        List<Board> tree = tree(memberId);
        Board board = owned(tree, boardId);
        String name = input.has("name") ? input.name() : board.getName();
        Long parentId = input.has("parentId") ? input.parentId() : board.getParentId();
        validate(tree, boardId, name, parentId);
        board.update(name, parentId, input.has("displayOrder") ? input.displayOrder() : board.getDisplayOrder());
        boards.flush();
        return board;
    }

    public void delete(long boardId, long memberId) {
        members.findActiveForWrite(memberId);
        List<Board> tree = tree(memberId);
        Board board = owned(tree, boardId);
        if (tree.stream().anyMatch(child -> Objects.equals(child.getParentId(), boardId))) {
            throw new ApplicationException(BoardError.RESOURCE_HAS_CHILDREN);
        }
        postBoards.detach(memberId, boardId);
        board.delete();
        boards.flush();
    }

    public List<Board> replaceTree(long memberId, BoardTreeChanges input) {
        members.findActiveForWrite(memberId);
        List<Board> tree = tree(memberId);
        Map<Long, Board> existing = new HashMap<>();
        for (Board board : tree) existing.put(board.getId(), board);
        Set<Long> retained = new HashSet<>();
        collectExisting(input.boards(), existing, retained, 1);
        Set<BoardTreeChanges.Existing> expected = new HashSet<>(input.base());
        Set<BoardTreeChanges.Existing> actual = new HashSet<>();
        for (Board board : tree) actual.add(new BoardTreeChanges.Existing(board.getId(), board.getName(),
                board.getParentId(), board.getDisplayOrder()));
        if (!expected.equals(actual)) throw new ApplicationException(BoardError.BOARD_TREE_CHANGED);
        applyNodes(memberId, null, input.boards(), existing);
        // 삭제하는 부모의 자식이 다른 곳으로 이동한 최종 트리를 먼저 반영한다.
        boards.flush();
        for (Board board : tree) {
            if (!retained.contains(board.getId())) {
                postBoards.detach(memberId, board.getId());
                board.delete();
            }
        }
        boards.flush();
        return tree(memberId);
    }

    private void collectExisting(List<BoardTreeChanges.Node> nodes, Map<Long, Board> existing, Set<Long> retained, int depth) {
        if (depth > 3 && !nodes.isEmpty()) throw invalid("parentId", "게시판은 최대 3단계까지 만들 수 있습니다.");
        Set<String> names = new HashSet<>();
        for (var node : nodes) {
            if (!names.add(node.name())) throw invalid("name", "같은 상위 게시판 안에서 이름이 중복될 수 없습니다.");
            if (node.id() != null) {
                if (!existing.containsKey(node.id())) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
                if (!retained.add(node.id())) throw invalid("id", "게시판 ID를 중복으로 사용할 수 없습니다.");
            }
            collectExisting(node.children(), existing, retained, depth + 1);
        }
    }

    private void applyNodes(long memberId, Long parentId, List<BoardTreeChanges.Node> nodes, Map<Long, Board> existing) {
        for (int order = 0; order < nodes.size(); order++) {
            var node = nodes.get(order);
            Board board;
            if (node.id() == null) board = boards.save(new Board(memberId, node.name(), parentId, order));
            else {
                board = existing.get(node.id());
                board.update(node.name(), parentId, order);
            }
            applyNodes(memberId, board.getId(), node.children(), existing);
        }
    }

    private List<Board> tree(long memberId) {
        // Post 쓰기와 같은 회원 행을 먼저 잠근 뒤 최신 트리를 읽는다.
        return boards.findByOwnerMemberIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(memberId);
    }

    private Board owned(List<Board> tree, long id) {
        return tree.stream().filter(board -> board.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
    }

    private void validate(List<Board> tree, Long id, String name, Long parentId) {
        if (parentId != null) owned(tree, parentId);
        if (tree.stream().anyMatch(board -> !Objects.equals(board.getId(), id)
                && Objects.equals(board.getParentId(), parentId) && board.getName().equals(name))) {
            throw invalid("name", "같은 상위 게시판 안에서 이름이 중복될 수 없습니다.");
        }
        Map<Long, Long> parents = new HashMap<>();
        for (Board board : tree) parents.put(board.getId(), board.getParentId());
        // 새 게시판은 아직 ID가 없으므로 루트까지의 깊이만 검증한다.
        if (id == null) {
            if (depth(parentId, parents) + 1 > 3) throw invalid("parentId", "게시판은 최대 3단계까지 만들 수 있습니다.");
        } else {
            parents.put(id, parentId);
            // 이동하는 게시판의 자손도 새 경로의 깊이와 순환 검증에 포함한다.
            for (Long boardId : parents.keySet()) {
                if (depth(boardId, parents) > 3) throw invalid("parentId", "하위 게시판을 포함해 최대 3단계까지 이동할 수 있습니다.");
            }
        }
    }

    private int depth(Long id, Map<Long, Long> parents) {
        Set<Long> visited = new HashSet<>();
        int depth = 0;
        while (id != null) {
            if (!visited.add(id)) throw invalid("parentId", "게시판의 상위 관계에 순환을 만들 수 없습니다.");
            depth++;
            id = parents.get(id);
        }
        return depth;
    }

    private ApplicationException invalid(String field, String reason) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, reason);
    }
}
