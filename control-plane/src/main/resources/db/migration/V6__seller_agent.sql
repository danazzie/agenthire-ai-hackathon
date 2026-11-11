-- Which of the seller's agents does an agent job (SellerSpecialty.id, e.g. 'tagline'). Null on jobs from before the
-- roster: the blurb writer did those. Validated in code, so adding an agent needs no migration.
alter table agent_job add column seller_agent text;
