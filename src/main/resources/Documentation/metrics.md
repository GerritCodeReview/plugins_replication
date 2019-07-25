# Metrics

Some metrics are emitted when replication occurs to a remote destination.
The granularity of the metrics recorded is at destination level, however when a particular project replication is flagged
as slow. This happens when the replication took longer than allowed threshold (see _remote.NAME.slowLatencyThreshold_ in [config.md](config.md))

The reason only slow metrics are published, rather than all, is to contain their number, which, on a big Gerrit installation
could potentially be considerably big.

### Project level

* plugins_replication_latency_slower_than_<threshold>_<destinationName>_<ProjectName> - Time spent pushing <ProjectName> to remote <destinationName> (in ms)

### Destination level

* plugins_replication_replication_delay_<destinationName> - Time spent waiting before pushing to remote <destinationName> (in ms)
* plugins_replication_replication_retries_<destinationName> - Number of retries when pushing to remote <destinationName>
* plugins_replication_replication_latency_<destinationName> - Time spent pushing to remote <destinationName> (in ms)

### Example
```
# HELP plugins_replication_replication_delay_destination Generated from Dropwizard metric import (metric=plugins/replication/replication_delay/destination, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_replication_delay_destination summary
plugins_replication_replication_delay_destinationName{quantile="0.5",} 65726.0
plugins_replication_replication_delay_destinationName{quantile="0.75",} 65726.0
plugins_replication_replication_delay_destinationName{quantile="0.95",} 65726.0
plugins_replication_replication_delay_destinationName{quantile="0.98",} 65726.0
plugins_replication_replication_delay_destinationName{quantile="0.99",} 65726.0
plugins_replication_replication_delay_destinationName{quantile="0.999",} 65726.0
plugins_replication_replication_delay_destinationName_count 3.0

# HELP plugins_replication_replication_retries_destination Generated from Dropwizard metric import (metric=plugins/replication/replication_retries/destination, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_replication_retries_destination summary
plugins_replication_replication_retries_destinationName{quantile="0.5",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.75",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.95",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.98",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.99",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.999",} 1.0
plugins_replication_replication_retries_destinationName_count 3.0

# HELP plugins_replication_replication_latency_destinationName Generated from Dropwizard metric import (metric=plugins/replication/replication_latency/destinationName, type=com.codahale.metrics.Timer)
# TYPE plugins_replication_replication_latency_destinationName summary
plugins_replication_replication_latency_destinationName{quantile="0.5",} 0.21199641400000002
plugins_replication_replication_latency_destinationName{quantile="0.75",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.95",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.98",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.99",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.999",} 0.321083881
plugins_replication_replication_latency_destinationName_count 2.0

# HELP plugins_replication_latency_slower_than_60_destinationName_projectName Generated from Dropwizard metric import (metric=plugins/replication/latency_slower_than/60/destinationName/projectName, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_latency_slower_than_60_destinationName_projectName summary
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.5",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.75",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.95",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.98",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.99",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName{quantile="0.999",} 278.0
plugins_replication_latency_slower_than_60_destinationName_projectName 1.0
```