package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.SearchResult;
import java.util.List;

public interface SearchRepository {
    List<SearchResult> search(String normalizedQuery, int limit);
}
