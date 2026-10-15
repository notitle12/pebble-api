package com.pebble.api.comment.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.comment.application.CommentService;
import com.pebble.api.comment.application.GuestCommentSecurity;
import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.comment.presentation.dto.CommentResponse;
import com.pebble.api.comment.presentation.dto.CommentWriteRequest;
import com.pebble.api.comment.presentation.dto.GuestCommentRequest;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/{resource:posts|projects}/{contentId:[0-9]+}/comments")
public class GuestCommentController {
    private final CommentService comments;
    private final GuestCommentSecurity security;
    private final GuestCommentIp addresses;

    @PostMapping(value="/guest",consumes="application/json")
    public ResponseEntity<ApiResponse<CommentResponse>> create(@PathVariable String resource,@PathVariable String contentId,
            @RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response) {
        prepare(request,response);
        GuestCommentRequest.validateCreate(body);
        String nickname=GuestCommentRequest.nickname(body),password=GuestCommentRequest.newPassword(body),text=GuestCommentRequest.body(body);
        long parent=CommentWriteRequest.id(contentId);
        String ip=addresses.resolve(request);
        security.check(ip,resource+":"+parent+":create",true);
        var comment=comments.createGuest(target(resource),parent,nickname,password,text);
        return ResponseEntity.created(URI.create("/api/v1/"+resource+"/"+contentId+"/comments/"+comment.getId()))
                .body(ApiResponse.of(CommentResponse.from(comment)));
    }
    @PostMapping(value="/{commentId:[0-9]+}/guest/update",consumes="application/json")
    public ApiResponse<CommentResponse> update(@PathVariable String resource,@PathVariable String contentId,@PathVariable String commentId,
            @RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response) {
        prepare(request,response);
        GuestCommentRequest.validate(body,Set.of("password","body"));
        String password=GuestCommentRequest.password(body),text=GuestCommentRequest.body(body);
        long parent=CommentWriteRequest.id(contentId),id=CommentWriteRequest.id(commentId);
        String ip=addresses.resolve(request);
        security.check(ip,resource+":"+id+":password",false);
        return ApiResponse.of(CommentResponse.from(comments.updateGuest(target(resource),parent,id,password,text)));
    }
    @PostMapping(value="/{commentId:[0-9]+}/guest/delete",consumes="application/json")
    public ResponseEntity<Void> delete(@PathVariable String resource,@PathVariable String contentId,@PathVariable String commentId,
            @RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response) {
        prepare(request,response);
        GuestCommentRequest.validate(body,Set.of("password"));
        String password=GuestCommentRequest.password(body);
        long parent=CommentWriteRequest.id(contentId),id=CommentWriteRequest.id(commentId);
        String ip=addresses.resolve(request);
        security.check(ip,resource+":"+id+":password",false);
        comments.deleteGuest(target(resource),parent,id,password);
        return ResponseEntity.noContent().build();
    }
    private void prepare(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(request.getQueryString()!=null)throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
    }
    private CommentTarget target(String resource){return resource.equals("posts")?CommentTarget.POST:CommentTarget.PROJECT;}
}
