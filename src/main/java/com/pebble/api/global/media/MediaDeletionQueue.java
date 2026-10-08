package com.pebble.api.global.media;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaDeletionQueue {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<R2ObjectStorage> storage;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stage(List<String> keys) {
        // 업로드 전에 별도 커밋하여 이후 파일 저장·DB 연결이 실패해도 회수한다.
        for (String key : keys) {
            jdbc.update(
                    "insert into media_deletion_job(storage_key,next_attempt_at) values (?,current_timestamp+interval '1 hour')",
                    key);
        }
    }

    public void lockStaged(List<String> keys) {
        // 연결 트랜잭션이 끝나기 전에는 작업자가 이 키를 삭제할 수 없다.
        for (String key : keys) {
            jdbc.queryForObject(
                    "select storage_key from media_deletion_job where storage_key=? for update",
                    String.class,
                    key);
        }
    }

    public void retain(List<String> keys) {
        for (String key : keys) {
            jdbc.update("delete from media_deletion_job where storage_key=?", key);
        }
    }

    @Transactional(readOnly = true)
    public List<String> dueKeys() {
        return jdbc.queryForList(
                "select storage_key from media_deletion_job where next_attempt_at<=current_timestamp order by next_attempt_at,storage_key limit 100",
                String.class);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteDue(String key) {
        var locked = jdbc.queryForList(
                "select storage_key from media_deletion_job where storage_key=? and next_attempt_at<=current_timestamp for update skip locked",
                String.class,
                key);
        if (locked.isEmpty()) return;
        Boolean referenced = jdbc.queryForObject(
                "select exists(select 1 from post where thumbnail_storage_key=? union all select 1 from project_media where storage_key=? or thumbnail_storage_key=? union all select 1 from member where profile_image_storage_key=? union all select 1 from post_body_image where storage_key=? union all select 1 from blog_link where logo_storage_key=?)",
                Boolean.class,
                key,
                key,
                key,
                key,
                key,
                key);
        if (Boolean.TRUE.equals(referenced)) {
            jdbc.update("delete from media_deletion_job where storage_key=?", key);
            return;
        }
        var client = storage.getIfAvailable();
        if (client == null) return;
        try {
            client.delete(key);
            jdbc.update("delete from media_deletion_job where storage_key=?", key);
        } catch (RuntimeException exception) {
            // 오류 본문·저장 키·자격 증명을 기록하지 않고 다음 후보에게 차례를 넘긴다.
            jdbc.update(
                    "update media_deletion_job set next_attempt_at=? where storage_key=?",
                    java.sql.Timestamp.from(Instant.now().plusSeconds(300)),
                    key);
        }
    }
}
