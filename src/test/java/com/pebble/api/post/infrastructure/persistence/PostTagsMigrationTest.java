package com.pebble.api.post.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import javax.sql.DataSource;
import java.util.UUID;
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
class PostTagsMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void preservesPostNamesAndProjectCatalogAssociationsWhenUpgrading() {
        String schema = "tags_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("24").load().migrate();
            String prefix = schema + ".";
            jdbc.update("insert into " + prefix + "member(id,nickname,status,created_at,updated_at) values (1,'migration','ACTIVE',now(),now())");
            jdbc.update("insert into " + prefix + "post(id,author_member_id,title,visibility_status,post_number,created_at,updated_at) values (2,1,'old post','PUBLIC',1,now(),now()),(3,1,'no tags','HIDDEN',2,now(),now())");
            jdbc.update("insert into " + prefix + "post_tag(post_id,tag_id,created_at) values (2,893467413652345598,now()),(2,893467413652345599,now())");
            jdbc.update("update " + prefix + "tag set status='INACTIVE' where id=893467413652345599");
            jdbc.update("insert into " + prefix + "project(id,owner_member_id,name,lifecycle_status,visibility_status,created_at,updated_at) values(4,1,'project','IN_PROGRESS','PUBLIC',now(),now())");
            jdbc.update("insert into " + prefix + "project_tag(project_id,tag_id,display_order,created_at) values(4,893467413652345598,0,now())");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("select array_to_string(tags, ',') from " + prefix + "post where id=2", String.class)).isEqualTo("Java,Spring Boot");
            assertThat(jdbc.queryForObject("select cardinality(tags) from " + prefix + "post where id=3", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select tech_stack_id from " + prefix + "project_tech_stack where project_id=4", Long.class)).isEqualTo(893467413652345598L);
            assertThat(jdbc.queryForObject("select status from " + prefix + "tech_stack where id=893467413652345599", String.class)).isEqualTo("INACTIVE");
            assertThat(jdbc.queryForObject("select to_regclass(?) is null", Boolean.class, prefix + "post_tag")).isTrue();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
