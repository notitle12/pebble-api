package com.pebble.api.techstack.presentation;

import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.techstack.application.TechStackQueryService;
import com.pebble.api.techstack.presentation.dto.TechStackResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tech-stacks")
@RequiredArgsConstructor
public class TechStackController {

    private final TechStackQueryService techStacks;
    @GetMapping
    public ApiResponse<List<TechStackResponse>> list() {
        return ApiResponse.of(techStacks.findPublicTechStacks().stream().map(techStack -> new TechStackResponse(
                techStack.getId().toString(), techStack.getName(), techStack.getSlug(), techStack.getDisplayOrder(), techStack.getStatus())).toList());
    }
}
