package com.pebble.api.post.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "post_block")
@Check(name = "ck_post_block_type", constraints = "block_type in ('TEXT', 'CODE', 'TABLE', 'ARCHITECTURE')")
@Check(name = "ck_post_block_content_length", constraints = "char_length(content) <= 50000")
@Check(name = "ck_post_block_order", constraints = "display_order >= 0")
@Check(name = "ck_post_block_table_language", constraints = "block_type <> 'TABLE' or language is null")
@Check(name = "ck_post_block_architecture_language", constraints = "block_type <> 'ARCHITECTURE' or language is null")
@Check(name = "ck_post_block_code_language", constraints = "block_type <> 'CODE' or language is not null")
public class PostBlock {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private Post post;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 16)
    private BlockType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private CodeLanguage language;

    @Column(length = 100)
    private String title;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    protected PostBlock() {
    }

    public PostBlock(Post post, BlockType type, String content, CodeLanguage language, String title, Integer displayOrder) {
        if (type == BlockType.CODE && language == null) {
            throw new IllegalArgumentException("Code block requires a language");
        }
        if ((type == BlockType.TABLE || type == BlockType.ARCHITECTURE) && language != null) {
            throw new IllegalArgumentException("Structured block must not have a code language");
        }
        if (displayOrder == null || displayOrder < 0) {
            throw new IllegalArgumentException("Display order must not be negative");
        }
        this.post = post;
        this.type = type;
        this.content = content;
        this.language = language;
        this.title = title;
        this.displayOrder = displayOrder;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
    }

    public Long getId() { return id; }
    public Post getPost() { return post; }
    public BlockType getType() { return type; }
    public String getContent() { return content; }
    public CodeLanguage getLanguage() { return language; }
    public String getTitle() { return title; }
    public Integer getDisplayOrder() { return displayOrder; }
}
