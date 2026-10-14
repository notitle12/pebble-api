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
class PostBoardOrderMigrationTest {
 @Autowired DataSource dataSource;
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void preservesExistingGlobalOrderAndInitializesEachBoardSeparately(){
  String schema="board_order_"+UUID.randomUUID().toString().replace("-","");JdbcTemplate jdbc=new JdbcTemplate(dataSource);
  try{
   Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("28").load().migrate();String p=schema+".";
   jdbc.update("insert into "+p+"member(id,nickname,status,created_at,updated_at) values(1,'test','ACTIVE',now(),now())");
   jdbc.update("insert into "+p+"board(id,owner_member_id,name,display_order,created_at,updated_at) values(10,1,'A',0,now(),now()),(11,1,'B',1,now(),now())");
   jdbc.update("insert into "+p+"post(id,author_member_id,board_id,title,visibility_status,post_number,display_order,created_at,updated_at) values(20,1,10,'a','PUBLIC',1,8,now(),now()),(21,1,10,'hidden','HIDDEN',2,2,now(),now()),(22,1,11,'b','PUBLIC',3,5,now(),now())");
   Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
   assertThat(jdbc.queryForList("select display_order from "+p+"post order by id",Integer.class)).containsExactly(8,2,5);
   assertThat(jdbc.queryForList("select board_display_order from "+p+"post order by id",Integer.class)).containsExactly(1,0,0);
  }finally{jdbc.execute("drop schema if exists "+schema+" cascade");}
 }
}
