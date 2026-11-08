-- Schema v1. Readable in sqlite3 without cloisters: words constrained by CHECK, times as ISO-8601 UTC text with
-- milliseconds (from the daemon's clock), no opaque blobs. The database enforces single-row and referential rules;
-- graph invariants (cycles, open children, live children of an archived page) are checked by the core in the write
-- transaction. Derived state (in progress, blocked, ready) is never stored.

CREATE TABLE project (
  key TEXT PRIMARY KEY
    CHECK (length(key) BETWEEN 2 AND 10 AND key GLOB '[A-Z]*' AND NOT key GLOB '*[^A-Z0-9]*'),
  name TEXT NOT NULL CHECK (name <> ''),
  next_number INTEGER NOT NULL DEFAULT 1 CHECK (next_number >= 1),
  created TEXT NOT NULL
) STRICT;

-- The shared id space: CLO-7 names exactly one issue or page. A number is issued once, to whichever is created.
CREATE TABLE item (
  project TEXT NOT NULL REFERENCES project (key),
  number INTEGER NOT NULL CHECK (number >= 1),
  kind TEXT NOT NULL CHECK (kind IN ('issue', 'page')),
  PRIMARY KEY (project, number),
  UNIQUE (project, number, kind)
) STRICT;

CREATE TABLE issue (
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  kind TEXT NOT NULL DEFAULT 'issue' CHECK (kind = 'issue'),
  title TEXT NOT NULL CHECK (title <> ''),
  body TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  resolution TEXT CHECK (resolution IN ('done', 'dropped')),
  closed TEXT,
  assignee TEXT CHECK (assignee <> ''),
  claimed TEXT,
  parent INTEGER,
  rank REAL NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (project, number),
  FOREIGN KEY (project, number, kind) REFERENCES item (project, number, kind),
  FOREIGN KEY (project, parent) REFERENCES issue (project, number),
  CHECK ((status = 'open') = (resolution IS NULL)),
  CHECK ((status = 'open') = (closed IS NULL)),
  CHECK ((assignee IS NULL) = (claimed IS NULL)),
  CHECK (parent <> number)
) STRICT;

CREATE INDEX issue_parent ON issue (project, parent);

-- Labels are case-insensitive: Bug and bug are one label.
CREATE TABLE label (
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  label TEXT NOT NULL COLLATE NOCASE CHECK (label <> ''),
  PRIMARY KEY (project, number, label),
  FOREIGN KEY (project, number) REFERENCES issue (project, number)
) STRICT;

-- blocker blocks blocked. Both are issues in one project.
CREATE TABLE blocks (
  project TEXT NOT NULL,
  blocker INTEGER NOT NULL,
  blocked INTEGER NOT NULL,
  PRIMARY KEY (project, blocker, blocked),
  FOREIGN KEY (project, blocker) REFERENCES issue (project, number),
  FOREIGN KEY (project, blocked) REFERENCES issue (project, number),
  CHECK (blocker <> blocked)
) STRICT;

CREATE INDEX blocks_blocked ON blocks (project, blocked);

-- A page's version is its latest revision number; r1 is its creation.
CREATE TABLE page (
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  kind TEXT NOT NULL DEFAULT 'page' CHECK (kind = 'page'),
  title TEXT NOT NULL CHECK (title <> ''),
  body TEXT NOT NULL DEFAULT '',
  parent INTEGER,
  rank REAL NOT NULL,
  archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (project, number),
  FOREIGN KEY (project, number, kind) REFERENCES item (project, number, kind),
  FOREIGN KEY (project, parent) REFERENCES page (project, number),
  CHECK (parent <> number)
) STRICT;

CREATE INDEX page_parent ON page (project, parent);

-- Mentions are stored edges from prose (an issue's title, body and comments; a page's title and body), rewritten in
-- the transaction that changes the prose. The target may not exist yet (a forward reference), so it names a project
-- and number, not an item; its kind is whatever item says once it exists.
CREATE TABLE mention (
  src_project TEXT NOT NULL,
  src_number INTEGER NOT NULL,
  dst_project TEXT NOT NULL REFERENCES project (key),
  dst_number INTEGER NOT NULL CHECK (dst_number >= 1),
  PRIMARY KEY (src_project, src_number, dst_project, dst_number),
  FOREIGN KEY (src_project, src_number) REFERENCES item (project, number),
  CHECK (src_project <> dst_project OR src_number <> dst_number)
) STRICT;

CREATE INDEX mention_dst ON mention (dst_project, dst_number);

-- One write, one event, ordered by a global sequence. The subject is the issue or page written to. An issue write's
-- comment and a page write's edit summary ride on its event; a content-changing page event carries the revision it
-- made, and a restore the revision it restored from. Kept forever.
CREATE TABLE event (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('issue', 'page')),
  at TEXT NOT NULL,
  actor TEXT NOT NULL CHECK (actor <> ''),
  comment TEXT CHECK (comment IS NULL OR kind = 'issue'),
  summary TEXT CHECK (summary IS NULL OR kind = 'page'),
  revision INTEGER CHECK (revision IS NULL OR (kind = 'page' AND revision >= 1)),
  restored_from INTEGER CHECK (restored_from IS NULL OR (revision IS NOT NULL AND restored_from BETWEEN 1 AND revision - 1)),
  FOREIGN KEY (project, number, kind) REFERENCES item (project, number, kind)
) STRICT;

CREATE INDEX event_subject ON event (project, number, seq);
CREATE UNIQUE INDEX event_revision ON event (project, number, revision) WHERE revision IS NOT NULL;

-- The field changes of one event, in order. Every field uses this one shape, all values as text.
CREATE TABLE change (
  event INTEGER NOT NULL REFERENCES event (seq),
  position INTEGER NOT NULL CHECK (position >= 1),
  field TEXT NOT NULL,
  op TEXT NOT NULL CHECK (op IN ('set', 'add', 'remove')),
  old TEXT,
  new TEXT,
  PRIMARY KEY (event, position)
) STRICT;

-- Issues and pages beside the subject whose timelines an event appears on.
CREATE TABLE event_related (
  event INTEGER NOT NULL REFERENCES event (seq),
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  PRIMARY KEY (event, project, number),
  FOREIGN KEY (project, number) REFERENCES item (project, number)
) STRICT;

CREATE INDEX event_related_item ON event_related (project, number, event);

-- text: is a case-insensitive substring match, over both kinds in one table keyed by item. Kept in step with the
-- prose by triggers, so it cannot drift from it.
CREATE VIRTUAL TABLE item_text USING fts5 (
  project UNINDEXED, number UNINDEXED, title, body, comments, tokenize = 'trigram'
);

CREATE TRIGGER issue_text_insert AFTER INSERT ON issue BEGIN
  INSERT INTO item_text (project, number, title, body, comments) VALUES (new.project, new.number, new.title, new.body, '');
END;

CREATE TRIGGER issue_text_update AFTER UPDATE OF title, body ON issue BEGIN
  UPDATE item_text SET title = new.title, body = new.body WHERE project = new.project AND number = new.number;
END;

CREATE TRIGGER page_text_insert AFTER INSERT ON page BEGIN
  INSERT INTO item_text (project, number, title, body, comments) VALUES (new.project, new.number, new.title, new.body, '');
END;

CREATE TRIGGER page_text_update AFTER UPDATE OF title, body ON page BEGIN
  UPDATE item_text SET title = new.title, body = new.body WHERE project = new.project AND number = new.number;
END;

CREATE TRIGGER comment_text AFTER INSERT ON event WHEN new.comment IS NOT NULL BEGIN
  UPDATE item_text
    SET comments = CASE comments WHEN '' THEN new.comment ELSE comments || char(10) || new.comment END
    WHERE project = new.project AND number = new.number;
END;
