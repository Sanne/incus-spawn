PRAGMA foreign_keys=OFF;
BEGIN TRANSACTION;
CREATE TABLE `message` (
          `id` text PRIMARY KEY,
          `session_id` text NOT NULL,
          `time_created` integer NOT NULL,
          `time_updated` integer NOT NULL,
          `data` text NOT NULL,
          CONSTRAINT `fk_message_session_id_session_id_fk` FOREIGN KEY (`session_id`) REFERENCES `session`(`id`) ON DELETE CASCADE
        );
INSERT INTO message VALUES('msg_1e4bfe050001YOStJMj76ryOpZ','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839142480,1794839142909,'{"role":"user","time":{"created":1794839142480},"agent":"build","model":{"providerID":"fake","modelID":"m"},"summary":{"diffs":[]}}');
INSERT INTO message VALUES('msg_1e4bfe1cb001a4Jp8cjtlStY3V','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839142859,1794839143173,'{"parentID":"msg_1e4bfe050001YOStJMj76ryOpZ","role":"assistant","mode":"build","agent":"build","path":{"cwd":"/tmp/h-oc/app","root":"/tmp/h-oc/app"},"cost":0,"tokens":{"total":15,"input":10,"output":5,"reasoning":0,"cache":{"write":0,"read":0}},"modelID":"m","providerID":"fake","time":{"created":1794839142859,"completed":1794839143173},"finish":"stop"}');
INSERT INTO message VALUES('msg_1e4c306fb001fSqG3uogZUNYPr','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839348987,1794839349290,'{"role":"user","time":{"created":1794839348987},"agent":"build","model":{"providerID":"fake","modelID":"m"},"summary":{"diffs":[]}}');
INSERT INTO message VALUES('msg_1e4c307f0001cLDIPIEaweRHPC','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839349233,1794839349526,'{"parentID":"msg_1e4c306fb001fSqG3uogZUNYPr","role":"assistant","mode":"build","agent":"build","path":{"cwd":"/tmp/h-oc/app","root":"/tmp/h-oc/app"},"cost":0,"tokens":{"total":15,"input":10,"output":5,"reasoning":0,"cache":{"write":0,"read":0}},"modelID":"m","providerID":"fake","time":{"created":1794839349233,"completed":1794839349526},"finish":"stop"}');
CREATE TABLE `part` (
          `id` text PRIMARY KEY,
          `message_id` text NOT NULL,
          `session_id` text NOT NULL,
          `time_created` integer NOT NULL,
          `time_updated` integer NOT NULL,
          `data` text NOT NULL,
          CONSTRAINT `fk_part_message_id_message_id_fk` FOREIGN KEY (`message_id`) REFERENCES `message`(`id`) ON DELETE CASCADE
        );
INSERT INTO part VALUES('prt_1e4bfe053001EWn1NDXnzs34Qk','msg_1e4bfe050001YOStJMj76ryOpZ','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839142484,1794839142485,'{"type":"text","text":"\"Fix the flaky test\""}');
INSERT INTO part VALUES('prt_1e4bfe2f5001ivLbFX4D2RvWzu','msg_1e4bfe1cb001a4Jp8cjtlStY3V','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839143157,1794839143158,'{"snapshot":"4b825dc642cb6eb9a060e54bf8d69288fbee4904","type":"step-start"}');
INSERT INTO part VALUES('prt_1e4bfe2f6001QwzLaJkO2Kd85H','msg_1e4bfe1cb001a4Jp8cjtlStY3V','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839143159,1794839143160,'{"type":"text","text":"## Done\nFixed the flaky test; PR #12 is open.","time":{"start":1794839143159,"end":1794839143160}}');
INSERT INTO part VALUES('prt_1e4bfe2fe001khsKqIdpdbf15e','msg_1e4bfe1cb001a4Jp8cjtlStY3V','ses_e1b401fbfffeFQlnqhgJkFmaHL',1794839143166,1794839143166,'{"reason":"stop","snapshot":"4b825dc642cb6eb9a060e54bf8d69288fbee4904","type":"step-finish","tokens":{"total":15,"input":10,"output":5,"reasoning":0,"cache":{"write":0,"read":0}},"cost":0}');
INSERT INTO part VALUES('prt_1e4c306fe001uwCY6edBaGSU2G','msg_1e4c306fb001fSqG3uogZUNYPr','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839348992,1794839348993,'{"type":"text","text":"Port the parser\n"}');
INSERT INTO part VALUES('prt_1e4c309060019IgGAacckqppYj','msg_1e4c307f0001cLDIPIEaweRHPC','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839349510,1794839349511,'{"snapshot":"4b825dc642cb6eb9a060e54bf8d69288fbee4904","type":"step-start"}');
INSERT INTO part VALUES('prt_1e4c30907001GFtYjufP38FbFV','msg_1e4c307f0001cLDIPIEaweRHPC','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839349511,1794839349513,'{"type":"text","text":"## Done\nFixed the flaky test; PR #12 is open.","time":{"start":1794839349511,"end":1794839349512}}');
INSERT INTO part VALUES('prt_1e4c3090f001Hih1d0KxH58vM4','msg_1e4c307f0001cLDIPIEaweRHPC','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839349519,1794839349519,'{"reason":"stop","snapshot":"4b825dc642cb6eb9a060e54bf8d69288fbee4904","type":"step-finish","tokens":{"total":15,"input":10,"output":5,"reasoning":0,"cache":{"write":0,"read":0}},"cost":0}');
CREATE TABLE `session` (
          `id` text PRIMARY KEY,
          `project_id` text NOT NULL,
          `workspace_id` text,
          `parent_id` text,
          `slug` text NOT NULL,
          `directory` text NOT NULL,
          `path` text,
          `title` text NOT NULL,
          `version` text NOT NULL,
          `share_url` text,
          `summary_additions` integer,
          `summary_deletions` integer,
          `summary_files` integer,
          `summary_diffs` text,
          `metadata` text,
          `cost` real DEFAULT 0 NOT NULL,
          `tokens_input` integer DEFAULT 0 NOT NULL,
          `tokens_output` integer DEFAULT 0 NOT NULL,
          `tokens_reasoning` integer DEFAULT 0 NOT NULL,
          `tokens_cache_read` integer DEFAULT 0 NOT NULL,
          `tokens_cache_write` integer DEFAULT 0 NOT NULL,
          `revert` text,
          `permission` text,
          `agent` text,
          `model` text,
          `time_created` integer NOT NULL,
          `time_updated` integer NOT NULL,
          `time_compacting` integer,
          `time_archived` integer,
          CONSTRAINT `fk_session_project_id_project_id_fk` FOREIGN KEY (`project_id`) REFERENCES `project`(`id`) ON DELETE CASCADE
        );
INSERT INTO session VALUES('ses_e1b401fbfffeFQlnqhgJkFmaHL','global',NULL,NULL,'cosmic-river','/tmp/h-oc/app','','## Done','1.19.5',NULL,0,0,0,NULL,NULL,0.0,10,5,0,0,0,NULL,'[{"permission":"question","pattern":"*","action":"deny"},{"permission":"plan_enter","pattern":"*","action":"deny"},{"permission":"plan_exit","pattern":"*","action":"deny"}]','build','{"id":"m","providerID":"fake","variant":"default"}',1794839142464,1794839143175,NULL,NULL);
INSERT INTO session VALUES('ses_e1b3cf965ffeecSPgR1UnAMh9b','global',NULL,NULL,'swift-river','/tmp/h-oc/app','','## Done','1.19.5',NULL,0,0,0,NULL,NULL,0.0,10,5,0,0,0,NULL,'[{"permission":"question","pattern":"*","action":"deny"},{"permission":"plan_enter","pattern":"*","action":"deny"},{"permission":"plan_exit","pattern":"*","action":"deny"}]','build','{"id":"m","providerID":"fake","variant":"default"}',1794839348890,1794839349528,NULL,NULL);
COMMIT;
