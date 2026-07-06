package io.github.liliangxu.phoneagent.codex;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

final class CodexConsoleContractSupport {
    static final String CODEX_PACKAGE = "io.github.liliangxu.phoneagent.codex.";

    private CodexConsoleContractSupport() {
    }

    static Class<?> codexClass(String simpleName) {
        try {
            return Class.forName(CODEX_PACKAGE + simpleName);
        } catch (ClassNotFoundException ex) {
            return fail("Missing designed production class " + CODEX_PACKAGE + simpleName);
        }
    }

    static Object enumConstant(String enumSimpleName, String constantName) {
        Class<?> enumType = codexClass(enumSimpleName);
        if (!enumType.isEnum()) {
            return fail(enumType.getName() + " must be an enum");
        }
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(constantName)) {
                return constant;
            }
        }
        return fail(enumType.getName() + " is missing enum constant " + constantName);
    }

    static Set<String> mappingPaths(Class<?> controllerType) {
        return Arrays.stream(controllerType.getDeclaredMethods())
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.path().length == 0 ? annotation.value() : annotation.path()))
                .collect(Collectors.toSet());
    }

    static Set<String> mappingMethods(Class<?> controllerType, String path) {
        return Arrays.stream(controllerType.getDeclaredMethods())
                .filter(method -> {
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) {
                        return false;
                    }
                    Set<String> paths = Arrays.stream(mapping.path().length == 0 ? mapping.value() : mapping.path())
                            .collect(Collectors.toSet());
                    return paths.contains(path);
                })
                .flatMap(method -> Arrays.stream(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class).method()))
                .map(Enum::name)
                .collect(Collectors.toSet());
    }

    static Set<String> exposedProperties(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getParameterCount() == 0)
                .map(Method::getName)
                .filter(name -> name.startsWith("get") || name.startsWith("is"))
                .map(CodexConsoleContractSupport::propertyName)
                .collect(Collectors.toSet());
    }

    static Object newRecordLike(String simpleName, Map<String, Object> overrides) {
        Class<?> type = codexClass(simpleName);
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] args = Arrays.stream(components)
                    .map(component -> valueFor(component.getName(), component.getType(), overrides))
                    .toArray();
            return construct(type, parameterTypes, args);
        }

        Constructor<?> noArgs = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 0)
                .findFirst()
                .orElse(null);
        if (noArgs == null) {
            return fail(simpleName + " must be constructible as a record or JavaBean for focused contract tests");
        }
        Object instance = construct(noArgs, new Object[0]);
        Map<String, Object> values = defaultValues(overrides);
        for (Method method : type.getMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterCount() != 1) {
                continue;
            }
            String property = propertyName(method.getName());
            if (values.containsKey(property)) {
                invoke(instance, method.getName(), values.get(property));
            }
        }
        return instance;
    }

    static Object construct(Class<?> type, Class<?>[] parameterTypes, Object[] args) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            return fail("Could not construct " + type.getName() + ": " + ex.getMessage());
        }
    }

    static Object constructAny(String simpleName, Object... args) {
        Class<?> type = codexClass(simpleName);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == args.length && parametersMatch(constructor.getParameterTypes(), args)) {
                return construct(constructor, args);
            }
        }
        return fail("No compatible constructor found for " + type.getName() + " with " + args.length + " argument(s)");
    }

    static Object invokeAny(Object target, String[] names, Object... args) {
        Class<?> type = target instanceof Class<?> classTarget ? classTarget : target.getClass();
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length
                        && parametersMatch(method.getParameterTypes(), args)) {
                    return invoke(target, method, args);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length
                        && parametersMatch(method.getParameterTypes(), args)) {
                    return invoke(target, method, args);
                }
            }
        }
        return fail("No compatible method named " + Arrays.toString(names) + " found on " + type.getName());
    }

    static Object invoke(Object target, String methodName, Object... args) {
        return invokeAny(target, new String[]{methodName}, args);
    }

    static Object property(Object target, String name) {
        Class<?> type = target.getClass();
        for (String methodName : new String[]{name, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1)}) {
            try {
                Method method = type.getMethod(methodName);
                if (method.getParameterCount() == 0) {
                    return invoke(target, method);
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next JavaBean/record accessor shape.
            }
        }
        return fail("No readable property " + name + " found on " + type.getName());
    }

    private static Object construct(Constructor<?> constructor, Object[] args) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            return fail("Could not construct " + constructor.getDeclaringClass().getName() + ": " + ex.getMessage());
        }
    }

    private static Object invoke(Object target, Method method, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, args);
        } catch (ReflectiveOperationException ex) {
            return fail("Could not invoke " + method.getName() + " on " + method.getDeclaringClass().getName()
                    + ": " + ex.getMessage());
        }
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> wrapped = wrap(parameterTypes[i]);
            if (!wrapped.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }

    private static String propertyName(String methodName) {
        String stem = methodName.startsWith("get") || methodName.startsWith("set")
                ? methodName.substring(3)
                : methodName.startsWith("is") ? methodName.substring(2) : methodName;
        return Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }

    private static Object valueFor(String name, Class<?> type, Map<String, Object> overrides) {
        Map<String, Object> values = defaultValues(overrides);
        if (values.containsKey(name) && values.get(name) != null && wrap(type).isInstance(values.get(name))) {
            return values.get(name);
        }
        if (type == String.class) {
            return values.getOrDefault(name, "value").toString();
        }
        if (type == int.class || type == Integer.class) {
            return values.getOrDefault(name, 49152);
        }
        if (type == long.class || type == Long.class) {
            return values.getOrDefault(name, 1_780_000_000L);
        }
        if (type == boolean.class || type == Boolean.class) {
            return values.getOrDefault(name, false);
        }
        if (type == Path.class) {
            return Path.of(values.getOrDefault(name, "/tmp/phone-agent").toString());
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.ofInstant(Instant.parse("2026-06-11T02:00:00Z"), ZoneOffset.UTC);
        }
        if (type == Instant.class) {
            return Instant.parse("2026-06-11T02:00:00Z");
        }
        if (type.isEnum() && "status".equals(name)) {
            return enumConstant(type.getSimpleName(), "RUNNING");
        }
        return null;
    }

    private static Map<String, Object> defaultValues(Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "cs-20260611-000001");
        values.put("title", "phone-agent");
        values.put("cwd", "/workspace/phone-agent");
        values.put("status", enumConstant("CodexSessionStatus", "RUNNING"));
        values.put("tmuxName", "phone-agent-codex-cs-20260611-000001");
        values.put("ttydPort", 49152);
        values.put("ttydUrl", "http://127.0.0.1:49152/");
        values.put("ttydPid", 12345L);
        values.put("threadId", "019e1234567890abcdef");
        values.put("threadShortId", "019e");
        values.put("jsonlPath", "/workspace/.codex/sessions/thread.jsonl");
        values.put("lastAssistantMessage", "ready for input");
        values.put("waitingMarker", false);
        values.put("lastRelevantEventTimestamp", "2026-06-11T10:00:00+08:00");
        values.put("lastProcessedJsonlSize", 0L);
        values.put("errorMessage", null);
        values.put("createdAt", OffsetDateTime.parse("2026-06-11T10:00:00+08:00"));
        values.put("updatedAt", OffsetDateTime.parse("2026-06-11T10:00:00+08:00"));
        values.put("startedAtEpochSecond", 1_780_000_000L);
        values.put("initialPromptSubmitted", false);
        values.putAll(overrides);
        return values;
    }
}
