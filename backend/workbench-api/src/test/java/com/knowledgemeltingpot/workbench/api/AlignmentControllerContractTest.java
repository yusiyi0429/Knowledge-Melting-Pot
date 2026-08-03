package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlignmentControllerContractTest {

    @Test
    void startRequestHasOnlyTypedIdentifiersAndAction() throws Exception {
        UUID baseRevisionId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        var request = new AlignmentController.StartAlignmentRequest(baseRevisionId,
                AlignmentAction.REGULATORY, List.of(materialId));

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json)
                .contains("\"baseRevisionId\":\"" + baseRevisionId + "\"")
                .contains("\"action\":\"REGULATORY\"")
                .contains("\"regulatoryMaterialIds\":[\"" + materialId + "\"]")
                .doesNotContain("prompt", "content", "markdown", "parameters");
        assertThat(Arrays.stream(AlignmentController.StartAlignmentRequest.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("baseRevisionId", "action", "regulatoryMaterialIds");
    }
}
