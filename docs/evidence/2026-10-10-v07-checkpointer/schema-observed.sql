CREATE TABLE `checkpoint_blobs` (
  `thread_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `checkpoint_ns` varchar(2000) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  `channel` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `version` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `type` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `blob` longblob,
  `checkpoint_ns_hash` binary(16) NOT NULL,
  PRIMARY KEY (`thread_id`,`checkpoint_ns_hash`,`channel`,`version`),
  KEY `checkpoint_blobs_thread_id_idx` (`thread_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `checkpoint_migrations` (
  `v` int NOT NULL,
  PRIMARY KEY (`v`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `checkpoint_writes` (
  `thread_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `checkpoint_ns` varchar(2000) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  `checkpoint_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `task_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `idx` int NOT NULL,
  `channel` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `type` varchar(150) COLLATE utf8mb4_bin DEFAULT NULL,
  `blob` longblob NOT NULL,
  `checkpoint_ns_hash` binary(16) NOT NULL,
  `task_path` varchar(2000) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  PRIMARY KEY (`thread_id`,`checkpoint_ns_hash`,`checkpoint_id`,`task_id`,`idx`),
  KEY `checkpoint_writes_thread_id_idx` (`thread_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `checkpoints` (
  `thread_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `checkpoint_ns` varchar(2000) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  `checkpoint_id` varchar(150) COLLATE utf8mb4_bin NOT NULL,
  `parent_checkpoint_id` varchar(150) COLLATE utf8mb4_bin DEFAULT NULL,
  `type` varchar(150) COLLATE utf8mb4_bin DEFAULT NULL,
  `checkpoint` json NOT NULL,
  `metadata` json NOT NULL DEFAULT (_utf8mb4'{}'),
  `checkpoint_ns_hash` binary(16) NOT NULL,
  PRIMARY KEY (`thread_id`,`checkpoint_ns_hash`,`checkpoint_id`),
  KEY `checkpoints_thread_id_idx` (`thread_id`),
  KEY `checkpoints_checkpoint_id_idx` (`checkpoint_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `probe_calls` (
  `name` varchar(80) COLLATE utf8mb4_bin NOT NULL,
  `calls` int NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `probe_effects` (
  `operation_key` varchar(80) COLLATE utf8mb4_bin NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_bin NOT NULL,
  `result_json` longtext COLLATE utf8mb4_bin,
  PRIMARY KEY (`operation_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
