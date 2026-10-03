-- db-scheduler task table (https://github.com/kagkarlsson/db-scheduler, PostgreSQL schema),
-- used by recurring maintenance jobs (ADR-025: carried over to Phase 3).
CREATE TABLE platform.scheduled_tasks (
  task_name            text        NOT NULL,
  task_instance        text        NOT NULL,
  task_data            bytea       NULL,
  execution_time       timestamptz NOT NULL,
  picked               boolean     NOT NULL,
  picked_by            text        NULL,
  last_success         timestamptz NULL,
  last_failure         timestamptz NULL,
  consecutive_failures integer     NULL,
  last_heartbeat       timestamptz NULL,
  version              bigint      NOT NULL,
  priority             smallint    NULL,
  CONSTRAINT pk_scheduled_tasks PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX ix_scheduled_tasks__execution_time ON platform.scheduled_tasks (execution_time);
CREATE INDEX ix_scheduled_tasks__last_heartbeat ON platform.scheduled_tasks (last_heartbeat);
CREATE INDEX ix_scheduled_tasks__priority_execution_time ON platform.scheduled_tasks (priority DESC, execution_time ASC);
