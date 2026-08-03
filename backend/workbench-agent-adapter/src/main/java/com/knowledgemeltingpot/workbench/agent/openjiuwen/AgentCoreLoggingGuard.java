package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.openjiuwen.core.common.logging.Loggers;
import com.openjiuwen.core.common.logging.LoggerProtocol;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Suppresses SDK INFO payload logs that contain prompts, responses or tool arguments. */
final class AgentCoreLoggingGuard {
    private static final int WARNING_LEVEL = 30;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private AgentCoreLoggingGuard() {
    }

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        List<LoggerProtocol> sensitiveLoggers = List.of(
                Loggers.AGENT,
                Loggers.LLM,
                Loggers.PROMPT,
                Loggers.PROMPT_BUILDER,
                Loggers.TOOL);
        sensitiveLoggers.forEach(logger -> logger.setLevel(WARNING_LEVEL));
    }
}
