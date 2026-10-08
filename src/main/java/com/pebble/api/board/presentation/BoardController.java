package com.pebble.api.board.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.board.application.BoardQueryService;
import com.pebble.api.board.application.BoardService;
import com.pebble.api.board.presentation.dto.BoardResponse;
import com.pebble.api.board.presentation.dto.BoardWriteRequest;
import com.pebble.api.board.presentation.dto.BoardTreeRequest;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequiredArgsConstructor
public class BoardController {
    private final BoardService boards;
    private final BoardQueryService boardQueries;

    @GetMapping("/api/v1/members/me/boards")
    public ApiResponse<List<BoardResponse>> mine(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam MultiValueMap<String, String> query) {
        checkNoQuery(query);
        return ApiResponse.of(BoardResponse.tree(boardQueries.mine(memberId(jwt))));
    }

    @PutMapping(value = "/api/v1/members/me/boards", consumes = "application/json")
    public ApiResponse<List<BoardResponse>> replaceTree(@AuthenticationPrincipal Jwt jwt,
            @RequestBody JsonNode request, @RequestParam MultiValueMap<String, String> query) {
        checkNoQuery(query);
        return ApiResponse.of(BoardResponse.tree(boards.replaceTree(memberId(jwt), BoardTreeRequest.parse(request))));
    }

    @GetMapping("/api/v1/members/{memberId:[0-9]+}/boards")
    public ApiResponse<List<BoardResponse.PublicBoardResponse>> publicTree(@PathVariable String memberId,
                                                                            @RequestParam MultiValueMap<String, String> query) {
        checkNoQuery(query);
        return ApiResponse.of(BoardResponse.PublicBoardResponse.tree(boardQueries.publicTree(
                BoardWriteRequest.id(memberId, "memberId"))));
    }

    @PostMapping(value = "/api/v1/boards", consumes = "application/json")
    public ResponseEntity<ApiResponse<BoardResponse.BoardWriteResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                               @RequestBody JsonNode request) {
        var board = boards.create(memberId(jwt), BoardWriteRequest.parse(request, true));
        var response = BoardResponse.BoardWriteResponse.from(board);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/boards/{boardId}")
                .buildAndExpand(response.id()).encode().toUri()).body(ApiResponse.of(response));
    }

    @PatchMapping(value = "/api/v1/boards/{boardId:[0-9]+}", consumes = "application/json")
    public ApiResponse<BoardResponse.BoardWriteResponse> update(@PathVariable String boardId, @AuthenticationPrincipal Jwt jwt,
                                               @RequestBody JsonNode request) {
        return ApiResponse.of(BoardResponse.BoardWriteResponse.from(boards.update(BoardWriteRequest.id(boardId, "boardId"),
                memberId(jwt), BoardWriteRequest.parse(request, false))));
    }

    @DeleteMapping("/api/v1/boards/{boardId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String boardId, @AuthenticationPrincipal Jwt jwt) {
        boards.delete(BoardWriteRequest.id(boardId, "boardId"), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    private long memberId(Jwt jwt) {
        return BoardWriteRequest.id(jwt.getSubject().substring("member:".length()), "memberId");
    }

    private void checkNoQuery(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
    }
}
