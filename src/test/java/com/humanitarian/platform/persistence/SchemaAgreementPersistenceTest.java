package com.humanitarian.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code columnDefinition} that names a PostgreSQL type must name one that
 * exists (D-4, Gate G3). Standard SQL types are skipped; anything else is looked
 * up in {@code pg_type}. Before D-4 seven fields named types such as
 * {@code help_type_enum} that had never existed.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
class SchemaAgreementPersistenceTest extends PersistenceTestSupport {

    private static final Set<String> STANDARD = Set.of("text", "numeric", "jsonb", "inet", "_text", "boolean", "integer", "bigint", "timestamp");

    @Test
    void everyColumnDefinitionNamesAnExistingType() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (BeanDefinition bd : scanner.findCandidateComponents("com.humanitarian.platform.model")) {
            Class<?> entity = Class.forName(bd.getBeanClassName());
            for (Field field : entity.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.columnDefinition().isBlank()) continue;
                String type = column.columnDefinition().trim().toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)$", "");
                if (STANDARD.contains(type) || type.startsWith("varchar")) continue;
                checked++;
                Number count = (Number) em.getEntityManager()
                        .createNativeQuery("SELECT count(*) FROM pg_type WHERE typname = :name")
                        .setParameter("name", type).getSingleResult();
                if (count.longValue() == 0) {
                    Table table = entity.getAnnotation(Table.class);
                    problems.add(entity.getSimpleName() + "." + field.getName() + " -> '" + type + "' (table "
                            + (table == null ? "?" : table.name()) + ")");
                }
            }
        }

        assertTrue(checked >= 8, "expected the enum-typed columns to be checked, checked " + checked);
        assertTrue(problems.isEmpty(), "columnDefinition names a type that does not exist:\n  " + String.join("\n  ", problems));
    }
}
