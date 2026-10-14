ALTER TABLE project ADD COLUMN document JSONB;
ALTER TABLE project ADD CONSTRAINT ck_project_document_object CHECK (document IS NULL OR jsonb_typeof(document)='object');
ALTER TABLE project_media DROP CONSTRAINT project_media_media_role_check;
ALTER TABLE project_media ADD CONSTRAINT project_media_media_role_check CHECK (media_role IN ('THUMBNAIL','SCREENSHOT','BODY'));
