package com.pebble.api.tag.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagError;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.text.Normalizer;
import java.util.Locale;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberTagService {
    private final TagRepository tags;
    private final MemberQueryService members;

    public Tag create(long memberId, String normalizedName) {
        members.findProfileCompletedForWrite(memberId);
        var existing = tags.findForNameReuse(normalizedName);
        if (!existing.isEmpty()) return requireActive(existing.getFirst());
        String slug = slug(normalizedName);
        // DB 고유 제약으로 동시 요청을 직렬화하고 충돌한 트랜잭션도 정상 상태로 유지한다.
        tags.insertMemberTagIfAbsent(TsidGenerator.generate(), normalizedName, slug, Instant.now());
        Tag tag = requireActive(tags.findBySlug(slug).orElseThrow());
        String existingName = Normalizer.normalize(tag.getName(), Normalizer.Form.NFKC).strip();
        if (existingName.startsWith("#")) existingName = existingName.substring(1);
        if (!existingName.toLowerCase(Locale.ROOT).equals(normalizedName)) {
            throw new ApplicationException(TagError.TAG_SLUG_CONFLICT);
        }
        return tag;
    }

    private Tag requireActive(Tag tag) {
        if (tag.getStatus() != TagStatus.ACTIVE) throw new ApplicationException(TagError.INACTIVE_TAG);
        return tag;
    }

    private String slug(String name) {
        try {
            return "user-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
