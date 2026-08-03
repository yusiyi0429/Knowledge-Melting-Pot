package com.knowledgemeltingpot.workbench.application.port;

import java.util.List;
import java.util.UUID;

public interface MaterialSelectionPort {
    List<MaterialSelection> findForExtraction(UUID roundId, UUID subSceneId);

    List<MaterialSelection> findForAlignment(UUID roundId, UUID subSceneId);

    List<MaterialSelection> findForQa(UUID roundId, UUID subSceneId);

    List<MaterialSelection> findForEvaluation(UUID roundId, UUID subSceneId);
}
