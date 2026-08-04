package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.application.service.SearchService;
import com.knowledgemeltingpot.workbench.domain.SearchResult;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public List<SearchResponse> search(@RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return search.search(q, limit).stream().map(SearchResponse::from).toList();
    }

    public record SearchResponse(String type, UUID sceneId, UUID subSceneId, UUID resourceId,
            String title, String excerpt) {
        static SearchResponse from(SearchResult value) {
            return new SearchResponse(value.type().name(), value.sceneId(), value.subSceneId(), value.resourceId(),
                    value.title(), value.excerpt());
        }
    }
}
