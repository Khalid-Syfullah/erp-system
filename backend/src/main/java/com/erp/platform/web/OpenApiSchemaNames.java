package com.erp.platform.web;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Unique schema names for the OpenAPI document (API.md §18). Springdoc names a schema after the simple
 * class name, so request and response records that share a name in different controllers (twelve
 * {@code LineRequest}s, a {@code Period} in Accounting and in Payroll) would collapse into one schema
 * and the generated frontend client would type all but one of them wrongly.
 *
 * <p>A type keeps its simple name when no other API type has it. Otherwise it is qualified by its
 * enclosing class without the {@code Controller}/{@code Responses}/{@code Requests}/{@code Views}/
 * {@code Commands}/{@code Reports} suffix ({@code PurchaseOrderController.LineRequest} →
 * {@code PurchaseOrderLineRequest}), or by its module when that leaves nothing
 * ({@code AccountingResponses.Period} → {@code AccountingPeriod}); if that is still ambiguous, by
 * the full enclosing class name.
 */
final class OpenApiSchemaNames {

    private static final Pattern CONTAINER_SUFFIX =
            Pattern.compile("(Controller|Responses|Requests|Views|Commands|Reports)$");

    private OpenApiSchemaNames() {}

    /** The API types reachable from the handler methods' bodies and return values. */
    static Set<Class<?>> reachableTypes(Collection<Method> handlers) {
        Deque<Type> pending = new ArrayDeque<>();
        for (Method method : handlers) {
            pending.add(method.getGenericReturnType());
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (method.getParameters()[i].isAnnotationPresent(RequestBody.class)) {
                    pending.add(method.getGenericParameterTypes()[i]);
                }
            }
        }
        Set<Class<?>> types = new LinkedHashSet<>();
        Set<Type> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (!seen.add(type)) {
                continue;
            }
            if (type instanceof ParameterizedType parameterized) {
                pending.add(parameterized.getRawType());
                pending.addAll(List.of(parameterized.getActualTypeArguments()));
            } else if (type instanceof WildcardType wildcard) {
                pending.addAll(List.of(wildcard.getUpperBounds()));
            } else if (type instanceof Class<?> cls) {
                if (cls.isArray()) {
                    pending.add(cls.getComponentType());
                } else if (cls.getName().startsWith("com.erp.") && !cls.isEnum()) {
                    types.add(cls);
                    if (cls.isRecord()) {
                        for (RecordComponent component : cls.getRecordComponents()) {
                            pending.add(component.getGenericType());
                        }
                    }
                }
            }
        }
        return types;
    }

    /**
     * The schema name of every type whose simple name is ambiguous. Generic wrappers ({@code
     * PageResponse<T>}) are named after their type arguments and are left out.
     */
    static Map<Class<?>, String> qualifiedNames(Set<Class<?>> types) {
        List<Class<?>> named =
                types.stream().filter(t -> t.getTypeParameters().length == 0).toList();
        Set<Class<?>> ambiguous = ambiguous(named, Class::getSimpleName);
        Set<String> taken = named.stream()
                .filter(t -> !ambiguous.contains(t))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
        Map<Class<?>, String> levelOne = new HashMap<>();
        ambiguous.forEach(t -> levelOne.put(t, qualified(t)));
        Set<Class<?>> stillAmbiguous = new HashSet<>(ambiguous(List.copyOf(ambiguous), levelOne::get));
        ambiguous.stream().filter(t -> taken.contains(levelOne.get(t))).forEach(stillAmbiguous::add);
        Map<Class<?>, String> names = new HashMap<>();
        for (Class<?> type : ambiguous) {
            names.put(type, stillAmbiguous.contains(type) ? fullyQualified(type) : levelOne.get(type));
        }
        return names;
    }

    private static Set<Class<?>> ambiguous(List<Class<?>> types, Function<Class<?>, String> name) {
        Map<String, List<Class<?>>> byName = types.stream().collect(Collectors.groupingBy(name));
        return byName.values().stream()
                .filter(group -> group.size() > 1)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
    }

    private static String qualified(Class<?> type) {
        String module = module(type);
        Class<?> enclosing = type.getEnclosingClass();
        String container = enclosing == null
                ? ""
                : CONTAINER_SUFFIX.matcher(enclosing.getSimpleName()).replaceAll("");
        if (container.isEmpty() || container.equalsIgnoreCase(module)) {
            container = capitalize(module);
        }
        return container + type.getSimpleName();
    }

    private static String fullyQualified(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        return (enclosing == null ? capitalize(module(type)) : enclosing.getSimpleName()) + type.getSimpleName();
    }

    private static String module(Class<?> type) {
        String[] segments = type.getName().split("\\.");
        return segments.length > 2 ? segments[2] : "";
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    /** Names schemas of ambiguous types (and generic wrappers of them) before springdoc's resolver runs. */
    static final class Converter implements ModelConverter {

        private final java.util.function.Supplier<Map<Class<?>, String>> names;

        Converter(java.util.function.Supplier<Map<Class<?>, String>> names) {
            this.names = names;
        }

        @Override
        public @Nullable Schema<?> resolve(
                AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
            if ((type.getName() == null || type.getName().isBlank()) && type.getType() != null) {
                String name = nameOf(Json.mapper().constructType(type.getType()));
                if (name != null) {
                    type.name(name);
                }
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        }

        private @Nullable String nameOf(JavaType type) {
            Class<?> raw = type.getRawClass();
            if (!raw.getName().startsWith("com.erp.") || type.isContainerType()) {
                return null;
            }
            if (type.containedTypeCount() == 0) {
                return names.get().get(raw);
            }
            StringBuilder name = new StringBuilder(raw.getSimpleName());
            boolean renamed = false;
            for (int i = 0; i < type.containedTypeCount(); i++) {
                JavaType argument = type.containedType(i);
                String argumentName = nameOf(argument);
                renamed |= argumentName != null;
                name.append(
                        argumentName != null
                                ? argumentName
                                : argument.getRawClass().getSimpleName());
            }
            return renamed ? name.toString() : null;
        }
    }
}
