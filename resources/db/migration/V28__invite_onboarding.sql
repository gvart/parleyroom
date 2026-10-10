-- Student invites can carry existing lesson materials and a level; on signup the
-- materials are shared with the new student and the level is copied to the user.

CREATE TABLE registration_materials
(
    registration_id UUID        NOT NULL REFERENCES registrations (id) ON DELETE CASCADE,
    material_id     UUID        NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (registration_id, material_id)
);

CREATE INDEX idx_registration_materials_material ON registration_materials (material_id);

ALTER TABLE registrations
    ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN level   LANGUAGE_LEVEL;

CREATE INDEX idx_registrations_user ON registrations (user_id);
