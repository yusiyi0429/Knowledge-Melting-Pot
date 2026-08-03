package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.knowledgemeltingpot.workbench.agent.AgentExecutionEventType;

record MappedSdkEvent(AgentExecutionEventType type, String text, String code) {
}
