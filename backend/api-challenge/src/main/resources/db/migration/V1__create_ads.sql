CREATE TABLE ads (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                 VARCHAR(100)  NOT NULL,
    description           TEXT          NOT NULL,
    price                 NUMERIC(12,2) NOT NULL CHECK (price > 0),
    category              VARCHAR(30)   NOT NULL
        CHECK (category IN ('eletronicos', 'moveis', 'veiculos', 'imoveis', 'esportes', 'colecionaveis')),
    images                TEXT[]        NOT NULL CHECK (cardinality(images) BETWEEN 1 AND 10),
    status                VARCHAR(20)   NOT NULL
        CHECK (status IN ('pending', 'published', 'rejected', 'moderation_failed')),
    rejection_reason      VARCHAR(50),
    moderation_attempts   INT           NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    last_moderation_error TEXT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Listing: keyset pagination per status, with and without the category filter.
CREATE INDEX idx_ads_status_created ON ads (status, created_at DESC, id DESC);
CREATE INDEX idx_ads_status_category_created ON ads (status, category, created_at DESC, id DESC);

-- Worker claim: only pending ads, so the index stays small as ads get moderated.
CREATE INDEX idx_ads_moderation_queue ON ads (next_attempt_at) WHERE status = 'pending';
