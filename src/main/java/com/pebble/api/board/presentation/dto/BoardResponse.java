package com.pebble.api.board.presentation.dto;

import com.pebble.api.board.domain.Board;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record BoardResponse(String id, String parentId, String name, int displayOrder, List<BoardResponse> children) {
    public static List<BoardResponse> tree(List<Board> boards) {
        Map<Long, List<Board>> children = new HashMap<>();
        for (Board board : boards) children.computeIfAbsent(board.getParentId(), ignored -> new ArrayList<>()).add(board);
        Comparator<Board> order = Comparator.comparingInt(Board::getDisplayOrder).thenComparing(Board::getId);
        children.values().forEach(items -> items.sort(order));
        return children.getOrDefault(null, List.of()).stream().map(board -> from(board, children)).toList();
    }

    public record BoardWriteResponse(String id, String parentId, String name, int displayOrder) {
        public static BoardWriteResponse from(Board board) {
            return new BoardWriteResponse(board.getId().toString(), board.getParentId() == null ? null : board.getParentId().toString(),
                    board.getName(), board.getDisplayOrder());
        }
    }

    private static BoardResponse from(Board board, Map<Long, List<Board>> children) {
        return new BoardResponse(board.getId().toString(), board.getParentId() == null ? null : board.getParentId().toString(),
                board.getName(), board.getDisplayOrder(), children.getOrDefault(board.getId(), List.of()).stream()
                .map(child -> from(child, children)).toList());
    }

    public record PublicBoardResponse(String id, String name, int displayOrder, List<PublicBoardResponse> children) {
        public static List<PublicBoardResponse> tree(List<Board> boards) {
            Map<Long, List<Board>> children = new HashMap<>();
            for (Board board : boards) children.computeIfAbsent(board.getParentId(), ignored -> new ArrayList<>()).add(board);
            Comparator<Board> order = Comparator.comparingInt(Board::getDisplayOrder).thenComparing(Board::getId);
            children.values().forEach(items -> items.sort(order));
            return children.getOrDefault(null, List.of()).stream().map(board -> from(board, children)).toList();
        }

        private static PublicBoardResponse from(Board board, Map<Long, List<Board>> children) {
            return new PublicBoardResponse(board.getId().toString(), board.getName(), board.getDisplayOrder(),
                    children.getOrDefault(board.getId(), List.of()).stream().map(child -> from(child, children)).toList());
        }
    }
}
