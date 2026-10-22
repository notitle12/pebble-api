package com.pebble.api.update.presentation;
import com.pebble.api.update.application.ServiceUpdateService;
import com.pebble.api.update.domain.UpdateType;
import com.pebble.api.global.presentation.response.ApiResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/updates")
public class UpdateController {
    private final ServiceUpdateService updates;
    @GetMapping
    public ApiResponse<?> list(@RequestParam MultiValueMap<String,String> query) {
        UpdateRequest.query(query, Set.of("page", "size", "type"));
        var page = updates.publicList(UpdateRequest.enumValue(UpdateType.class, query.getFirst("type")), UpdateRequest.page(query), UpdateRequest.size(query));
        return ApiResponse.of(UpdateResponse.Listing.of(page, page.getContent().stream().map(UpdateResponse.Summary::of).toList()));
    }
    @GetMapping("/{id:[0-9]+}")
    public ApiResponse<?> detail(@PathVariable String id, @RequestParam MultiValueMap<String,String> query) {
        UpdateRequest.query(query, Set.of());
        return ApiResponse.of(UpdateResponse.Detail.of(updates.publicDetail(UpdateRequest.id(id))));
    }
}
