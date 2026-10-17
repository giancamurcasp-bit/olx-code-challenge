-- When the ad entered the moderation queue: how long the seller has been waiting. Set once by the
-- database on insert and never updated, unlike updated_at, which every attempt (a 500, a 429)
-- moves forward, and unlike created_at, which the seed import keeps from the original ad.
ALTER TABLE ads ADD COLUMN moderation_queued_at TIMESTAMPTZ;
UPDATE ads SET moderation_queued_at = created_at; -- existing ads: the best estimate there is
ALTER TABLE ads
    ALTER COLUMN moderation_queued_at SET NOT NULL,
    ALTER COLUMN moderation_queued_at SET DEFAULT now();
