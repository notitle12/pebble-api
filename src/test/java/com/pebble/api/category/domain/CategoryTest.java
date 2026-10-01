package com.pebble.api.category.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CategoryTest {

    @Test
    void rejectsAThirdLevelBeforePersistence() {
        Category root = new Category(null, "Backend", "backend", 0, CategoryStatus.ACTIVE);
        Category child = new Category(root, "Web", "web", 0, CategoryStatus.ACTIVE);

        assertThatThrownBy(() -> new Category(child, "API", "api", 0, CategoryStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
