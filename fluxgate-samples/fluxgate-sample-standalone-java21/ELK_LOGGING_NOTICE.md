# Notice

## File-based JSON logging for ELK integration

This application writes the logs of `org.fluxgate.spring.filter.FluxgateRateLimitFilter` as
**JSON** (`LogstashEncoder` from `net.logstash.logback:logstash-logback-encoder`, with the custom
fields `"service": "fluxgate-sample"` and `"component": "rate-limiter"`), configured in
`src/main/resources/logback-spring.xml`. They go to two appenders:

- `JSON_FILE`: `${user.dir}/log/fluxgate.log`
- `LOGSTASH`: a TCP connection to Logstash on `localhost:5044`

These logs can be collected by log shippers such as **Filebeat** or **Fluent Bit**, and forwarded to
**Logstash / Elasticsearch** for centralized logging and analysis.

---

## Important notes

- `${user.dir}` is the working directory of the JVM. `spring-boot:run` starts the application in
  the module's base directory, so with
  `./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-standalone-java21` (run from the
  project root) the file is `fluxgate-samples/fluxgate-sample-standalone-java21/log/fluxgate.log`.
  With `java -jar`, it is `log/fluxgate.log` under the directory you start the JVM from.
- Logback creates the missing `log/` directory on startup; the process needs write permission
  for it.
- The filter logger has `additivity="false"`, so its lines appear **only** in the JSON file and in
  Logstash, not on the console.
- Without a Logstash on `localhost:5044` the `LOGSTASH` appender keeps reconnecting (every 30
  seconds) and logs a warning each time; the file appender is not affected.

---

## Typical ELK integration flow

Application

- JSON log file (${user.dir}/log/fluxgate.log)
- Filebeat / Fluent Bit
- Logstash
- Elasticsearch

This approach allows log collection to be **decoupled from the application process**, improving runtime stability and
making it suitable for production-grade ELK pipelines.
