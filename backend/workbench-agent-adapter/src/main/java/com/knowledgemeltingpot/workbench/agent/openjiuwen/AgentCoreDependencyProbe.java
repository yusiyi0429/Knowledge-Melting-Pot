package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.operator.OperatorStream;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.workflow.Workflow;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/** Fail-fast compatibility probe for the intentionally pinned SDK. */
final class AgentCoreDependencyProbe {
    static final String EXPECTED_VERSION = "0.1.13";
    private static final String POM_PROPERTIES =
            "META-INF/maven/com.openjiuwen/agent-core-java/pom.properties";

    private AgentCoreDependencyProbe() {
    }

    static void verify() {
        String actualVersion = readVersion();
        if (!EXPECTED_VERSION.equals(actualVersion)) {
            throw new IllegalStateException(
                    "Unsupported agent-core-java version: expected " + EXPECTED_VERSION + ", got " + actualVersion);
        }
        requireIteratorMethod(ReActAgent.class, "stream", Object.class, Session.class, List.class);
        requireIteratorMethod(Workflow.class, "stream", Object.class, Object.class, ModelContext.class, List.class);
        requireMethod(OutputSchema.class, "getType");
        requireMethod(OutputSchema.class, "getPayload");
        if (!AutoCloseable.class.isAssignableFrom(OperatorStream.class)) {
            throw new IllegalStateException("agent-core-java OperatorStream must remain AutoCloseable");
        }
    }

    static String readVersion() {
        ClassLoader classLoader = AgentCoreDependencyProbe.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(POM_PROPERTIES)) {
            if (input == null) {
                throw new IllegalStateException("agent-core-java pom.properties is missing");
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "unknown");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read agent-core-java version", exception);
        }
    }

    private static void requireIteratorMethod(Class<?> owner, String name, Class<?>... parameters) {
        Method method = requireMethod(owner, name, parameters);
        if (!Iterator.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException(owner.getName() + "#" + name + " no longer returns Iterator");
        }
    }

    private static Method requireMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Required agent-core-java API is missing: " + owner.getName() + "#" + name,
                    exception);
        }
    }
}
