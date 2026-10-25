-- PROTOTYPE schema. The item table owns the shared id space: CLO-7 names exactly one issue or page.
CREATE TABLE project (
  key TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  next_number INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE item (
  project TEXT NOT NULL REFERENCES project(key),
  number INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('issue', 'page')),
  PRIMARY KEY (project, number),
  UNIQUE (project, number, kind)
);

CREATE TABLE issue (
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  kind TEXT NOT NULL DEFAULT 'issue' CHECK (kind = 'issue'),
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open',
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (project, number),
  FOREIGN KEY (project, number, kind) REFERENCES item(project, number, kind)
);

CREATE TABLE page (
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  kind TEXT NOT NULL DEFAULT 'page' CHECK (kind = 'page'),
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  parent INTEGER,
  rank REAL NOT NULL,
  archived INTEGER NOT NULL DEFAULT 0,
  created TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (project, number),
  FOREIGN KEY (project, number, kind) REFERENCES item(project, number, kind),
  FOREIGN KEY (project, parent) REFERENCES page(project, number)
);

CREATE TABLE event (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  project TEXT NOT NULL,
  number INTEGER NOT NULL,
  at TEXT NOT NULL,
  actor TEXT NOT NULL,
  type TEXT NOT NULL,
  revision INTEGER,
  summary TEXT,
  FOREIGN KEY (project, number) REFERENCES item(project, number)
);

CREATE UNIQUE INDEX event_revision ON event(project, number, revision) WHERE revision IS NOT NULL;

CREATE TABLE change (
  seq INTEGER NOT NULL REFERENCES event(seq),
  field TEXT NOT NULL,
  before TEXT,
  after TEXT,
  PRIMARY KEY (seq, field)
);

CREATE TABLE mention (
  src_project TEXT NOT NULL,
  src_number INTEGER NOT NULL,
  dst_project TEXT NOT NULL REFERENCES project(key),
  dst_number INTEGER NOT NULL,
  PRIMARY KEY (src_project, src_number, dst_project, dst_number),
  FOREIGN KEY (src_project, src_number) REFERENCES item(project, number)
);

CREATE INDEX mention_dst ON mention(dst_project, dst_number);

INSERT INTO project (key, name) VALUES ('CLO', 'Cloisters');
