# Notice

## File-based JSON logging for ELK integration

This application supports **file-based JSON logging**, which can be used for **ELK stack integration**.

Structured JSON logs are written to the following location:

${user.dir}/log/fluxgate.log

These logs can be collected by log shippers such as **Filebeat** or **Fluent Bit**, and forwarded to **Logstash /
Elasticsearch** for centralized logging and analysis.

---

## Important notes

- The log directory **must exist before application startup**.
- **Logback does not automatically create directories or log files**.
- If the directory does not exist at startup, file-based logging will not be initialized.
- Ensure the application process has sufficient write permissions for the log directory.

This behavior follows Logback’s default file appender policy.

---

## Typical ELK integration flow

Application

- JSON log file (${user.dir}/log/fluxgate.log)
- Filebeat / Fluent Bit
- Logstash
- Elasticsearch

This approach allows log collection to be **decoupled from the application process**, improving runtime stability and
making it suitable for production-grade ELK pipelines.
