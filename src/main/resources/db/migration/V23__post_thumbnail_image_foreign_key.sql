ALTER TABLE post
    ADD CONSTRAINT fk_post_thumbnail_image
        FOREIGN KEY (thumbnail_image_id)
        REFERENCES post_body_image (id)
        ON DELETE SET NULL;
