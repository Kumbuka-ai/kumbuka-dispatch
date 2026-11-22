-- ===========================================================================
-- V18: the content of a sent task, frozen in `task` as well (DEC-0014).
--
-- V17 froze the texts of a sent task by putting them in a table of their own,
-- and left the commission's attributes on `task` unguarded. `exchange` guards
-- them since V4 (title, apparatus) and V7 (dispatch_metadata); this migration
-- returns that protection for the new store.
--
-- What is frozen, and from when:
--
-- * `title`, `apparatus` and `dispatch_metadata`, once the row BEFORE the
--   change is past `draft`. The change that sends a draft still sets them,
--   because the guard reads the old state, not the new one.
-- * `created_at` and `created_by`, always. They are the database's stamps of
--   the insert and have no state in which they change.
--
-- Everything else on `task` stays writable after the send: the state, hold,
-- holder and lease columns, `return_metadata` and the change stamps are the
-- lifecycle, which goes on moving (DEC-0014). Which other columns might belong
-- under the freeze is not decided, and they are deliberately left out.
--
-- ADDITIVE (DEC-0018): one function and one trigger, nothing else. No running
-- code reads or writes `task` yet, so the image before this one is untouched.
-- ===========================================================================

CREATE FUNCTION dispatch.refuse_task_content_writes_after_send() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION
            'task % is frozen in created_at and created_by: they are stamped at the insert '
            'and never change, in any state.',
            OLD.id
            USING ERRCODE = 'raise_exception';
    END IF;

    IF OLD.state <> 'draft'
       AND (NEW.title IS DISTINCT FROM OLD.title
            OR NEW.apparatus IS DISTINCT FROM OLD.apparatus
            OR NEW.dispatch_metadata IS DISTINCT FROM OLD.dispatch_metadata) THEN
        RAISE EXCEPTION
            'task % is frozen: it is %, and title, apparatus and dispatch_metadata are changed '
            'only while it is a draft. Corrections attach as an addendum.',
            OLD.id, OLD.state
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_content_frozen_after_send
    BEFORE UPDATE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_task_content_writes_after_send();
