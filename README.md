# Maestro

Maestro is a Kubernetes Redis controller for autoscaling a Redis cluster running inside of a Kubernetes cluster. 

## Requirements

Before using Maestro, the following requirements need to be met:

* A running Kubernetes cluster
* A Kubernetes StatefulSet Redis cluster deployment with at least six nodes (three masters and three slaves replicating each master)
* The Kubernetes API running inside of the Kubernetes cluster, and resolvable by the environment variable `SKUBER_URL` on the pod running the Maestro microservice

## Overview

Maestro utilizes [adenda/cornucopia](https://github.com/adenda/cornucopia) to add and remove Redis nodes from the Redis cluster. It polls Redis cluster nodes individually to be able to detect when the average memory usage across all Redis nodes reaches a configurable scaling threshold. When such a threshold is reached and maintained for a configured period of time, Maestro triggers a scaling up or a scaling down of the Redis cluster.

## Application configuration

### Maestro configuration

| Setting  | Description  |
|:----------|:--------------|
| `kubernetes.statefulset-name` | The name of the StatefulSet Kubernetes resource managing the Redis cluster |

### Redis configuration settings

| Setting  | Description  |
|:----------|:--------------|
| `redis.cluster.seed.server.host` | Initial node-hostname from which the full cluster topology will be derived (default: localhost). It is recommended to use a Kubernetes Service resource that resolves to one of the Redis cluster pods. |
| `redis.cluster.seed.server.port` | Initial node-port from which the full cluster topology will be derived (default: 7000). |
| `redis.sampler.max.memory` | The maximum memory usage for Redis nodes before the scale-up threshold is reached, which is taken as the average across all Redis nodes. |
| `redis.sampler.min.memory` | The minimum memory usage for Redis nodes before the scale-down threshold is reached, which is taken as the average across all Redis nodes. |
| `redis.sampler.scaleup.threshold` | The number of consecutive memory samples that are counted while the sampled memory is above the maximum memory threshold after which a scale-up of the Redis cluster is initiated. |
| `redis.sampler.scaledown.threshold` | The number of consecutive memory samples that are counted while the sampled memory is below the minimum memory threshold after which a scale-down of the Redis cluster is initiated. |
| `redis.sampler.interval` | The sampling period for memory samples. |


