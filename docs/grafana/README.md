# Grafana dashboard for Reactive Commons (RabbitMQ)

`reactive-commons-live-dashboard.json` is a Grafana dashboard as code for the Micrometer metrics exported by
the RabbitMQ transport: publishing, consuming, retries, discards, queue depth, back-pressure and the shared
scheduler.

The dashboard was validated against a Prometheus scraping a sender and a listener application: every panel
whose data exists returns series. Panels that stay empty are series registered on first use — `async_query_*`
before the first `requestReply`, `consumer.restart`/`replies.lost`/`flow.resubscribe` before the first occurrence,
HTTP non-success before the first non-2xx, and GC pause before the first collection. That is expected and spelled
out in the dashboard's About panel.

All panels of both dashboards were validated against a live Prometheus: every query returns data, except the
failure panels (`status!="completed"`, `outcome!="SUCCESS"`, `handler_error`, discards, etc.), which stay empty
until the corresponding failure happens — that is expected.

## Requirements in the application

```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

The library registers its meters on the application's `MeterRegistry`; with the Prometheus registry on the
classpath, Spring Boot exposes them at `/actuator/prometheus`.

## Quick start (local showcase)

1. Start an application that uses the RabbitMQ transport (see `samples/`), listening on port `8080`. Generate
   some traffic: send events/commands, force a handler error, let a query expire.
2. From this directory:

   ```bash
   docker compose up -d
   ```

3. Open Grafana at <http://localhost:3000> (anonymous admin) → **Dashboards → Reactive Commons → Reactive Commons —
   RabbitMQ**. Prometheus is at <http://localhost:9090>.

The scrape target assumes the application is reachable at `host.docker.internal:8080`; adjust `prometheus.yml` if
it runs elsewhere.

## Import into an existing Grafana

1. **Dashboards → New → Import**, upload `reactive-commons-live-dashboard.json`.
2. Pick the Prometheus datasource in the `Prometheus` variable at the top (or import with a datasource UID).
3. Optionally filter by `Domain` and `Queue` (multi-select, `All` by default).

## Metric names

Micrometer names are exported to Prometheus with dots replaced by underscores and type suffixes
(`_total` for counters, `_seconds_count`/`_seconds_sum`/`_seconds_max` for timers, `_count`/`_sum`/`_max` for
distribution summaries):

| Micrometer meter | Prometheus series | Panels |
|---|---|---|
| `reactive_commons.publish.outcome` | `reactive_commons_publish_outcome_total{outcome,confirm,domain}` | Publish outcomes /s |
| `reactive_commons.publish.latency` | `reactive_commons_publish_latency_seconds_{count,sum,max}` | Publish latency |
| `reactive_commons.publish.message.size` | `reactive_commons_publish_message_size_{count,sum,max}` | Published message size |
| `reactive_commons.publish.flow.live` | `reactive_commons_publish_flow_live{kind,domain}` | Publish flows and hand-over queue |
| `reactive_commons.publish.flow.resubscribe` | `reactive_commons_publish_flow_resubscribe_total{kind,domain}` | Publish flow resubscriptions /s |
| `reactive_commons.publish.hand_over.queue.size` | `reactive_commons_publish_hand_over_queue_size{domain}` | Publish flows and hand-over queue |
| `reactive_commons.publish.connection.blocked.state` | `reactive_commons_publish_connection_blocked_state{domain}` | Broker blocking publishes |
| `reactive_commons.publish.unroutable.messages` | `reactive_commons_publish_unroutable_messages_total` | Unroutable messages /s |
| `reactive_commons.publish.unroutable.emit.failed` | `reactive_commons_publish_unroutable_emit_failed_total` | Unroutable messages /s |
| `reactive_commons.publish.unroutable.process.error` | `reactive_commons_publish_unroutable_process_error_total` | Unroutable messages /s |
| `reactive_commons.consumer.messages` | `reactive_commons_consumer_messages_total{outcome,queue,domain}` | Consumer outcomes /s, success ratio |
| `reactive_commons.consumer.handler.latency` | `reactive_commons_consumer_handler_latency_seconds_*` | Handler latency |
| `reactive_commons.consumer.handler.wait` | `reactive_commons_consumer_handler_wait_seconds_*` | Handler wait |
| `reactive_commons.consumer.retry.attempts` | `reactive_commons_consumer_retry_attempts_total{queue,domain}` | Retries /s |
| `reactive_commons.consumer.retry.exhausted` | `reactive_commons_consumer_retry_exhausted_total{queue,domain}` | Retries /s |
| `reactive_commons.consumer.redelivered` | `reactive_commons_consumer_redelivered_total{queue,domain}` | Retries /s |
| `reactive_commons.consumer.handler.timeout` | `reactive_commons_consumer_handler_timeout_total{queue,domain}` | Retries /s |
| `reactive_commons.consumer.query.discarded` | `reactive_commons_consumer_query_discarded_total{reason,queue,domain}` | Discards and query anomalies /s |
| `reactive_commons.consumer.query.reply.failed` | `reactive_commons_consumer_query_reply_failed_total{queue,domain}` | Discards and query anomalies /s |
| `reactive_commons.consumer.discard.notify.failed` | `reactive_commons_consumer_discard_notify_failed_total{queue,domain}` | Discards and query anomalies /s |
| `reactive_commons.consumer.query.invalid.timeout_header` | `reactive_commons_consumer_query_invalid_timeout_header_total{queue,domain}` | Discards and query anomalies /s |
| `reactive_commons.consumer.restart` | `reactive_commons_consumer_restart_total{reason,queue,domain}` | Listener restarts /s |
| `reactive_commons.consumer.active` | `reactive_commons_consumer_active{queue,domain}` | Active consumers |
| `reactive_commons.consumer.replies.lost` | `reactive_commons_consumer_replies_lost_total{domain}` | Replies lost /s |
| `reactive_commons.consumer.message.size` | `reactive_commons_consumer_message_size_{count,sum,max}` | Consumed message size |
| `reactive_commons.consumer.queue.depth` | `reactive_commons_consumer_queue_depth{queue,domain}` | Queue depth, backlog |
| `reactive_commons.schedulers.handler.pool.size` | `reactive_commons_schedulers_handler_pool_size` | Handler pool threads |
| `reactive_commons.schedulers.handler.threads.active` | `reactive_commons_schedulers_handler_threads_active` | Handler pool threads |
| `reactive_commons.schedulers.handler.queue.size` | `reactive_commons_schedulers_handler_queue_size` | Queued handler tasks |
| `reactive_commons.schedulers.handler.growth` | `reactive_commons_schedulers_handler_growth_total` | Handler pool growth /s |

## Things to know

- **Series appear on first use.** Micrometer registers a counter with its first increment: a failure panel shows
  `No data` until the first failure happens, which is healthy. Latency/size summaries are created with the
  listener/sender, but have no data until messages flow.
- **Averages, not percentiles.** Timers and summaries expose count/sum/max. To get p95/p99, enable percentile
  histograms and use `histogram_quantile(..._bucket)`:

  ```yaml
  management:
    metrics:
      distribution:
        percentiles-histogram:
          reactive_commons.publish.latency: true
          reactive_commons.consumer.handler.latency: true
          reactive_commons.consumer.handler.wait: true
  ```

- **Queue depth** is `queue.declare-ok`'s message count, read with a passive declare every 10 seconds. It counts
  **ready** messages: deliveries already handed to a consumer but not acknowledged are not included, so a busy
  prefetch window can hide backlog from this gauge. Combine it with handler wait and the scheduler panels.
- **Scheduler meters are JVM-wide** (the handler pool is shared by every listener), so they carry no `domain` or
  `queue` tag.
- The **Unroutable** panels are not filtered by domain: the default `UnroutableMessageNotifier` is a singleton
  shared by every domain.
