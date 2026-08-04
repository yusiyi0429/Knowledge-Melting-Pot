package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;

/**
 * Shared JSONB codec for chunk locators. The locator is always produced by a
 * parser and stored by the persistence layer; it is never accepted from a
 * client in its JSON form.
 */
final class ChunkLocators {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChunkLocators() {
    }

    static ChunkLocator deserialize(String json) throws JacksonException {
        return OBJECT_MAPPER.readValue(json, ChunkLocator.class);
    }
}
