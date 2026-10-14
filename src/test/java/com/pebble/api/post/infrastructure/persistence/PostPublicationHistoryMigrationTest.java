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
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PostPublicationHistoryMigrationTest {
    @Autowired DataSource dataSource;
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void upgradesPreviouslyPublishedHiddenAndDeletedPostsWithoutCountingDrafts() {
        String schema="publication_upgrade_"+UUID.randomUUID().toString().replace("-","");
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("27").load().migrate();
            String prefix=schema+".";
            jdbc.update("insert into "+prefix+"member(id,nickname,status,created_at,updated_at) values(1,'migration','ACTIVE',now(),now())");
            jdbc.update("insert into "+prefix+"post(id,author_member_id,title,visibility_status,post_number,is_draft,published_at,created_at,updated_at) values(2,1,'public','PUBLIC',1,false,now(),now(),now()),(3,1,'hidden','HIDDEN',2,false,now(),now(),now()),(4,1,'deleted','DELETED',3,false,now(),now(),now()),(5,1,'draft','HIDDEN',4,true,null,now(),now()),(6,1,'unpublished','HIDDEN',5,false,null,now(),now())");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList("select post_id from "+prefix+"post_publication_history order by post_id",Long.class)).containsExactly(2L,3L,4L);
            jdbc.update("delete from "+prefix+"post");
            assertThat(jdbc.queryForObject("select count(*) from "+prefix+"post_publication_history",Long.class)).isEqualTo(3);
            jdbc.update("delete from "+prefix+"member");
            assertThat(jdbc.queryForObject("select count(*) from "+prefix+"post_publication_history",Long.class)).isZero();
        } finally { jdbc.execute("drop schema if exists "+schema+" cascade"); }
    }
}
