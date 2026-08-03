package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import java.util.Iterator;

interface SdkJobExecutor extends AutoCloseable {
    SdkTerminalResult execute();

    Iterator<?> stream();

    default void cancel() {
    }

    @Override
    default void close() {
    }
}
