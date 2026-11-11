ALTER TABLE project_media ADD CONSTRAINT uk_project_media_id_project UNIQUE (id, project_id);

ALTER TABLE project ADD COLUMN gallery_cover_media_id BIGINT;
ALTER TABLE project ADD CONSTRAINT fk_project_gallery_cover_media
    FOREIGN KEY (gallery_cover_media_id, id)
    REFERENCES project_media (id, project_id)
    ON DELETE SET NULL (gallery_cover_media_id);
