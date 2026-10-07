-- The profile locale is the user's chosen user-interface language and formatting locale
-- (docs/LOCALIZATION.md, ADR-043). NULL means "not chosen": the web application then shows Bangla
-- (bn-BD), the default language. Until now the column defaulted to 'en' and no screen let users
-- choose a language, so 'en' was never a choice: it is cleared. Other values (typed in the former
-- free-text "Language and region" field, e.g. en-GB) were chosen and stay.
ALTER TABLE auth.users
  ALTER COLUMN locale DROP NOT NULL,
  ALTER COLUMN locale DROP DEFAULT;

UPDATE auth.users SET locale = NULL WHERE locale = 'en';
