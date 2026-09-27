-- ============================================================================
-- BOSS Database Schema: clients may not write user_passkeys at all
-- ============================================================================
-- File: 20260927210000_restrict_user_passkeys_client_writes.sql
--
-- `authenticated` and `anon` hold table-level INSERT and UPDATE on
-- public.user_passkeys (20251023000014_grants.sql), and the row policies
-- ("Users can insert their own passkeys", "Users can update their own
-- passkeys") let an account write its own rows. A table-level grant covers
-- every column, and those policies gate the ROW, not the columns within it,
-- so anyone holding a user's bearer token can write the columns the `passkey`
-- edge function later trusts for verification:
--
--   * UPDATE public_key preserving credential_id - replaces the enrolled key
--     with an attacker key under the existing credential id, giving durable
--     passkey access to the account with no enrollment ceremony.
--   * INSERT a fresh row with an attacker public_key - the same outcome
--     without touching the existing credential: the INSERT policy checks only
--     auth.uid() = user_id, which a stolen bearer satisfies.
--   * UPDATE sign_count to 0 - resets the clone-detection counter
--     (20260725000000), so a cloned authenticator no longer trips the
--     non-increase rule.
--   * UPDATE public_key_alg or rp_id - mis-describes the stored key or
--     re-pins the credential to another relying party.
--
-- A stolen bearer already authorizes the function's /register/challenge
-- route, so the gap closed here is specifically the bypass of the ceremony
-- and attestation path: direct table writes create or alter trust material
-- the verification route then relies on.
--
-- Fix: no client role may INSERT or UPDATE public.user_passkeys at all.
-- Unlike 20260927120000 (plugins), where authors legitimately edit ordinary
-- columns and the fix is column-level, every legitimate writer of this table
-- is the `passkey` edge function, which connects with the service-role key
-- (index.ts) and so bypasses RLS and these grants: enrollment inserts,
-- sign_count/last_used_at updates on assertion, display_name renames, and
-- active=false soft deletes all run as service_role. No client in this
-- repository writes the table directly, so there is no column list to
-- preserve and the whole privilege is withdrawn.
--
-- The auto-updatable view public.active_user_passkeys (security_invoker =
-- on, GRANT ALL in 20251023000014) is a second write path into the same
-- table: it exposes credential_id and last_used_at, and with the view grant
-- intact a client could still UPDATE those base columns through it. Its
-- INSERT, UPDATE and DELETE are withdrawn for the same client roles; SELECT
-- stays, because listing one's own active passkeys through this view is the
-- intended client read path.
--
-- SELECT and DELETE on the base table are unchanged. SELECT is how clients
-- read their own rows; DELETE can only remove the caller's own passkey
-- (self-denial, no trust material created or altered), and removing a row
-- cannot be combined with a re-INSERT now that INSERT is closed.
--
-- How: mirror 20260927120000. A table-level grant cannot be narrowed by
-- revoking one column, so where a client role holds table-level INSERT or
-- UPDATE the grant is revoked wholesale and nothing is re-granted. The block
-- fails closed if any column-level INSERT or UPDATE privilege survives
-- through PUBLIC or an inherited role.
--
-- Consequence for later migrations: passkey management clients must keep
-- going through the `passkey` edge function; a future direct client write
-- path needs its own migration re-granting the specific columns it needs,
-- and user_passkeys_client_writes_test.sql pins the closed set until then.
-- ============================================================================

DO $restrict_user_passkeys_client_writes$
DECLARE
    passkeys_oid oid := pg_catalog.to_regclass('public.user_passkeys');
    view_oid oid := pg_catalog.to_regclass('public.active_user_passkeys');
    client_role text;
    privilege text;
    exposed name;
BEGIN
    IF passkeys_oid IS NULL THEN
        RAISE EXCEPTION 'public.user_passkeys is missing; this migration expects the passkey schema';
    END IF;

    FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;

        FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE'] LOOP
            -- has_table_privilege is true only for a table-level grant (or
            -- ownership), never because of column-level grants, so this
            -- touches exactly the grants being replaced.
            IF pg_catalog.has_table_privilege(client_role, passkeys_oid, privilege) THEN
                EXECUTE pg_catalog.format(
                    'REVOKE %s ON TABLE public.user_passkeys FROM %I', privilege, client_role);
            END IF;

            -- Fail closed if any column of the table stays writable through
            -- PUBLIC or an inherited role: revoking the client's direct ACL
            -- entry cannot remove either source.
            SELECT a.attname INTO exposed
            FROM pg_catalog.pg_attribute a
            WHERE a.attrelid = passkeys_oid
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND pg_catalog.has_column_privilege(client_role, passkeys_oid, a.attname, privilege)
            LIMIT 1;

            IF exposed IS NOT NULL THEN
                RAISE EXCEPTION USING
                    errcode = '42501',
                    message = pg_catalog.format(
                        'client %s retains %s on public.user_passkeys.%I',
                        client_role, privilege, exposed),
                    hint = 'Revoke the privilege from PUBLIC or the inherited role.';
            END IF;
        END LOOP;

        -- The view is auto-updatable; without this, its GRANT ALL would keep
        -- credential_id and last_used_at client-writable into the same table.
        IF view_oid IS NOT NULL THEN
            FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE', 'DELETE'] LOOP
                IF pg_catalog.has_table_privilege(client_role, view_oid, privilege) THEN
                    EXECUTE pg_catalog.format(
                        'REVOKE %s ON TABLE public.active_user_passkeys FROM %I',
                        privilege, client_role);
                END IF;

                IF pg_catalog.has_table_privilege(client_role, view_oid, privilege) THEN
                    RAISE EXCEPTION USING
                        errcode = '42501',
                        message = pg_catalog.format(
                            'client %s retains %s on public.active_user_passkeys',
                            client_role, privilege),
                        hint = 'Revoke the privilege from PUBLIC or the inherited role.';
                END IF;
            END LOOP;
        END IF;
    END LOOP;
END;
$restrict_user_passkeys_client_writes$;
