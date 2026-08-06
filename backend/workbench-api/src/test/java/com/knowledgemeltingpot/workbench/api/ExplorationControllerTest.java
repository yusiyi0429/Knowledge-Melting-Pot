package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.ExplorationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class ExplorationControllerTest {
    @Test
    void deletionArchivesTheExplorationForTheCurrentUser() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ExplorationService service = mock(ExplorationService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        Authentication authentication = mock(Authentication.class);
        when(currentUser.id(authentication)).thenReturn(actorId);
        ExplorationController controller = new ExplorationController(service, currentUser);

        var response = controller.delete(sessionId, authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).delete(sessionId, actorId, "");
    }
}
