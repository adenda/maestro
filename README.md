# Maestro

Maestro is a Redis controller for autoscaling a Redis cluster running inside of a Kubernetes cluster. 

## Requirements

Maestro is a stand-alone microservice that sends commands to Redis cluster nodes deployed as part of a Statefulset Kubernetes resource. Before using Maestro, the following requirements need to be met:

* A running Kubernetes cluster
* A Kubernetes Statefulset Redis deployment with six nodes (three masters and three slaves replicating each master)
* A Kubernetes Service resource that acts as a standing query to any of the Kubernetes pods to be used as a seed server
* The Kubernetes API running inside of the Kubernetes cluster, and resolvable by the environment variable `SKUBER_URL` on the pod running the Maestro microservice

## Overview

Maestro utilizes [adenda/cornucopia](https://github.com/adenda/cornucopia) for performing the task of joining new Redis nodes to the Redis cluster and resharding, or redistributing hash slots evenly across all master nodes. Maestro itself polls Redis cluster nodes individually to be able to detect when memory usage across all Redis nodes reaches a configurable scaling threshold. When such a threshold is reached and maintained for a long enough period of time, Maestro triggers a scaling of the Redis cluster.

The scaling up of a Redis cluster by Maestro involves first scaling up the size of the Kubernetes Statefulset. Secondly, Maestro sends a message to Cornucopia to either join a master node or a slave node to the cluster. Master nodes and slaves nodes are added in alternating intervals. If the newly joined node is a master node, then Cornucopia computes a reshard table that evenly distributes hash slots across all of the Redis nodes, and then migrates the required number of hash slots to the newly joined Redis node from the pre-existing master nodes. If the newly joined node is a slave node, then Cornucopia automatically assigns this node to replicate the poorest master.

### Limitations

Maestro currently does not support scaling down a Redis cluster. This will be a feature released in the future.

## Application configuration

### Maestro configuration

| Setting  | Description  |
|:----------|:--------------|
| `kubernetes.statefulset-name` | The name of the Statefulset Kubernetes resource managing the Redis cluster |
| `kubernetes.conductor.polling-period` | The period in seconds that the Kubernetes Redis cluster Statefulset is polled for newly added Pods to be ready to join to the cluster |
| `kubernetes.new-nodes-number` | The number of new Redis nodes to add whenever the Redis cluster scales up |

### Cornucopia configuration

| Setting  | Description  |
|:----------|:--------------|
| `cornucopia.refresh.timeout` | Time (seconds) to wait for cluster topology changes to propagate (default: 5 seconds).  |
| `cornucopia.batch.period` | Time (seconds) to wait for batches to accumulate before executing a job (default: 5 seconds). |
| `cornucopia.http.host` | The hostname where the Cornucopia microservice is run (default: localhost). |
| `cornucopia.http.port` | The port on which the Cornucopia microservice is run (default: 9001). |
| `cornucopia.reshard.interval` | Mininum time (seconds) to wait between reshard events (default: 60 seconds). |
| `cornucopia.reshard.timeout` | The maximum upper time limit (seconds) that the cluster must be resharded within without the resharding failing (default: 300 seconds). |
| `cornucopia.migrate.slot.timeout` | The maximum upper time limit (seconds) that a slot must be migrated from one node to another during resharding without slot migration failing. (default: 60 seconds) |

### Redis configuration settings

| Setting  | Description  |
|:----------|:--------------|
| `redis.cluster.seed.server.host` | Initial node-hostname from which the full cluster topology will be derived (default: localhost). |
| `redis.cluster.seed.server.port` | Initial node-port from which the full cluster topology will be derived (default: 7000). |
| `redis.sampler.max.memory` | The maximum memory usage for Redis nodes before the scaleup threshold is reached, which is taken as the average across all Redis nodes |
| `redis.sampler.scaleup.threshold` | The number of consecutive memory samples that are counted above the maximum memory usage (above) before triggering the Redis cluster to scale up |
| `redis.sampler.interval` | The period in seconds where a memory sample is taken |
