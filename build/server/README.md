# When Server __VERSION__

This archive contains only the When runtime. Supply external Redis and ETCD
endpoints through environment variables before starting it. Kafka and OTLP are
optional and remain external services.

Copy `conf/application.example.yml` for a readable configuration reference,
export the matching environment variables, then use:

```text
bin/when run
bin/when start --config conf/application.example.yml
bin/when status
bin/when stop
```

The `--config` option checks that the reference file is readable; environment
variables are the authoritative runtime source in this release.
