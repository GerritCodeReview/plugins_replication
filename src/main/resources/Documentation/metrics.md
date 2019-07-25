# Metrics

Some metrics are emitted when replication occurs to a remote destination.
The recording of the metrics happens at both _remote destination_ and _project_ granularity, as follows:

### Project level

* plugins_replication_replication_delay_<destinationName>_<ProjectName> - Time spent waiting before pushing <ProjectName> to remote <destinationName> (in ms)
* plugins_replication_replication_retries_<destinationName>_<ProjectName> - Number of retries when pushing <ProjectName> to remote <destinationName>
* plugins_replication_replication_latency_<destinationName>_<ProjectName> - Time spent pushing <ProjectName> to remote <destinationName> (in ms)

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
# HELP plugins_replication_replication_delay_destinationName_ProjectName Generated from Dropwizard metric import (metric=plugins/replication/replication_delay/destinationName_ProjectName, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_replication_delay_destinationName_ProjectName summary
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.5",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.75",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.95",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.98",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.99",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName{quantile="0.999",} 5005.0
plugins_replication_replication_delay_destinationName_ProjectName_count 1.0

# HELP plugins_replication_replication_retries_destination Generated from Dropwizard metric import (metric=plugins/replication/replication_retries/destination, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_replication_retries_destination summary
plugins_replication_replication_retries_destinationName{quantile="0.5",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.75",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.95",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.98",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.99",} 1.0
plugins_replication_replication_retries_destinationName{quantile="0.999",} 1.0
plugins_replication_replication_retries_destinationName_count 3.0
# HELP plugins_replication_replication_retries_destinationName_ProjectName Generated from Dropwizard metric import (metric=plugins/replication/replication_retries/destinationName_ProjectName, type=com.codahale.metrics.Histogram)
# TYPE plugins_replication_replication_retries_destinationName_ProjectName summary
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.5",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.75",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.95",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.98",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.99",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName{quantile="0.999",} 0.0
plugins_replication_replication_retries_destinationName_ProjectName_count 1.0

# HELP plugins_replication_replication_latency_destinationName Generated from Dropwizard metric import (metric=plugins/replication/replication_latency/destinationName, type=com.codahale.metrics.Timer)
# TYPE plugins_replication_replication_latency_destinationName summary
plugins_replication_replication_latency_destinationName{quantile="0.5",} 0.21199641400000002
plugins_replication_replication_latency_destinationName{quantile="0.75",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.95",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.98",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.99",} 0.321083881
plugins_replication_replication_latency_destinationName{quantile="0.999",} 0.321083881
plugins_replication_replication_latency_destinationName_count 2.0
# HELP plugins_replication_replication_latency_destinationName_ProjectName Generated from Dropwizard metric import (metric=plugins/replication/replication_latency/destinationName_ProjectName, type=com.codahale.metrics.Timer)
# TYPE plugins_replication_replication_latency_destinationName_ProjectName summary
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.5",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.75",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.95",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.98",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.99",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName{quantile="0.999",} 0.320374579
plugins_replication_replication_latency_destinationName_ProjectName_count 1.0
```