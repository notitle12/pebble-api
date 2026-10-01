package com.pebble.api.member.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MemberProfileMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void upgradesDuplicateNicknamesWithoutTakingAnExistingSuffixedName() {
        String schema = "member_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            jdbc.update("insert into " + schema + ".member (id,nickname,status,created_at,updated_at) values "
                    + "(1,'name','ACTIVE','2026-01-01','2026-01-01'),(2,'name','ACTIVE','2026-01-02','2026-01-02'),"
                    + "(3,'name-2','ACTIVE','2026-01-03','2026-01-03'),(4,?,'ACTIVE','2026-01-04','2026-01-04'),"
                    + "(5,?,'ACTIVE','2026-01-05','2026-01-05')", "한".repeat(30), "한".repeat(30));
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList("select nickname from " + schema + ".member where id <= 3 order by id", String.class))
                    .containsExactly("name", "name-3", "name-2");
            assertThat(jdbc.queryForObject("select char_length(nickname) from " + schema + ".member where id=5", Integer.class)).isEqualTo(30);
            assertThat(jdbc.queryForList("select handle from " + schema + ".member", String.class)).containsOnlyNulls();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".member where profile_completed_at is null", Long.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("select count(distinct nickname) from " + schema + ".member", Long.class)).isEqualTo(5);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
