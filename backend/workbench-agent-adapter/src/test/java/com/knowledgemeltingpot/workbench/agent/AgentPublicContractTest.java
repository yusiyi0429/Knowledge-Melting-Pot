package com.knowledgemeltingpot.workbench.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPublicContractTest {
    private static final List<Class<?>> PUBLIC_BOUNDARY = List.of(
            AgentExecutionRequest.class,
            AgentExecutionResult.class,
            AgentExecutionEvent.class,
            AgentRuntimeLifecycle.class,
            AgentRuntimeFactory.class,
            KnowledgeExtractionPort.class,
            DefaultKnowledgeExtractionAdapter.class,
            AgentModelConfiguration.class,
            ModelProvider.class);

    @Test
    void publicBoundaryDoesNotExposeSdkMapOrObjectContracts() {
        for (Class<?> type : PUBLIC_BOUNDARY) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || isObjectProtocol(method)) {
                    continue;
                }
                assertAllowed(type, method.getName() + " return", method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertAllowed(type, method.getName() + " parameter", parameterType);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    continue;
                }
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertAllowed(type, "constructor parameter", parameterType);
                }
            }
        }
    }

    @Test
    void requestCannotAcceptClientControlledJobOrSessionIds() {
        List<String> components = Arrays.stream(AgentExecutionRequest.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .toList();

        assertFalse(components.stream().anyMatch(name -> name.contains("jobid") || name.contains("job_id")));
        assertFalse(components.stream().anyMatch(name -> name.contains("session")));
    }

    @Test
    void sensitivePayloadsAreOmittedFromDefaultStringRepresentations() {
        AgentExecutionRequest request = new AgentExecutionRequest(
                "workspace-1", "actor-1", "confidential source", AgentExecutionMode.REACT);
        assertFalse(request.toString().contains("confidential source"));

        AgentExecutionEvent event = new AgentExecutionEvent(
                "job-1",
                "session-1",
                0,
                AgentExecutionEventType.TEXT_DELTA,
                "confidential result",
                "text_delta",
                java.time.Instant.EPOCH);
        assertFalse(event.toString().contains("confidential result"));
    }

    private static boolean isObjectProtocol(Method method) {
        return method.getName().equals("equals")
                || method.getName().equals("hashCode")
                || method.getName().equals("toString");
    }

    private static void assertAllowed(Class<?> owner, String location, Class<?> exposedType) {
        String message = owner.getName() + " " + location + " exposes " + exposedType.getName();
        assertNotEquals(Object.class, exposedType, message);
        assertNotEquals(Map.class, exposedType, message);
        assertTrue(!exposedType.getName().startsWith("com.openjiuwen."), message);
    }
}
