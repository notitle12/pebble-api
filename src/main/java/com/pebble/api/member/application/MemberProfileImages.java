package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.ImageProcessor;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.member.domain.Member;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberProfileImages {
    private final ImageProcessor images;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;

    public void replace(Member member, byte[] source) {
        var client = storage.getIfAvailable();
        if (client == null) throw new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
        var processed = images.process(source);
        String key = "member/" + UUID.randomUUID() + "/profile.webp";
        var keys = List.of(key);
        // 기존 미디어와 동일하게 실패·롤백된 업로드는 삭제 큐가 회수한다.
        deletion.stage(keys);
        deletion.lockStaged(keys);
        client.put(key, processed.thumbnail());
        member.changeProfileImage(key);
        deletion.retain(keys);
    }

    public String url(Member member) {
        if (member.getProfileImageStorageKey() == null) return member.getProfileImageUrl();
        var client = storage.getIfAvailable();
        return client == null ? null : client.signedUrl(member.getProfileImageStorageKey());
    }
}
