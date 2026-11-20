-- UC-04: sex is one of a fixed set (the API enforces it too). NULL stays allowed for incomplete profiles.
ALTER TABLE health.profiles
  ADD CONSTRAINT profiles_sex_check CHECK (sex IS NULL OR sex IN ('FEMALE','MALE','OTHER','UNSPECIFIED'));
