package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.SearchRepository;
import com.knowledgemeltingpot.workbench.domain.SearchResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {
    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(String query, int limit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() < 2 || normalized.length() > 100) {
            throw new IllegalArgumentException("search query must contain between 2 and 100 characters");
        }
        return repository.search(normalized, Math.min(Math.max(limit, 1), 50));
    }
}
