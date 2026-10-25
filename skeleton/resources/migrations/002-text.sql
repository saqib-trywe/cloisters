-- PROTOTYPE. One FTS5 trigram table over both kinds, keyed by item; text: is a substring match.
CREATE VIRTUAL TABLE item_text USING fts5(project UNINDEXED, number UNINDEXED, title, body, tokenize = 'trigram');
