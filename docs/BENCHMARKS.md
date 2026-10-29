# Benchmarks

All numbers come from the built-in load generator:

```bash
java -jar target/distributed-kv-store.jar bench --url=http://localhost:8080
```

Defaults: 8 client threads, 1,000 keys, 100-byte values, 80% reads / 20% writes,
10,000 warm-up requests (discarded) followed by 40,000 measured requests.

## Setup

| | |
|---|---|
| Machine | Laptop, Intel Core i5-9300HF (4 cores / 8 threads), 16 GB RAM, Linux |
| JDK | Temurin 21.0.13 |
| Cluster | 3 nodes + router, started with `scripts/start-cluster.sh` |
| Load | Machine was not idle (load average 5-8 from other apps during the runs) |

Everything ran on **one machine**: the benchmark client, the router and all
three nodes share the same 8 hardware threads. There is no real network
between them. The absolute numbers are therefore low and only useful for
comparing the setups below with each other.

## Results

| Setup | Throughput | Read p50 | Read p99 | Write p50 | Write p99 |
|---|---:|---:|---:|---:|---:|
| Single node, direct | 11,522 ops/s | 0.49 ms | 2.96 ms | 0.57 ms | 3.32 ms |
| Router, 3 nodes, N=1 | 4,231 ops/s | 1.30 ms | 7.98 ms | 1.48 ms | 8.13 ms |
| Router, 3 nodes, N=2, W=1 | 4,267 ops/s | 1.21 ms | 6.19 ms | 2.11 ms | 9.59 ms |

### fsync cost (single node, 100% writes, 10,000 requests)

| Setting | Throughput | Write p50 | Write p99 |
|---|---:|---:|---:|
| `FSYNC=false` (default) | 3,904 ops/s | 1.74 ms | 6.81 ms |
| `FSYNC=true` | 322 ops/s | 20.74 ms | 46.87 ms |

## What the numbers show

- **The router costs about 2.7x throughput here.** Each client request turns
  into two HTTP calls (client to router, router to node), and four JVMs plus
  the client compete for the same CPU. On separate machines the extra hop
  would mostly add network latency, not CPU load.
- **Replication slows writes, not reads.** Going from N=1 to N=2, read latency
  stays the same because a read still goes to one replica. Write p50 goes
  from 1.48 ms to 2.11 ms because the router writes to both replicas one
  after the other.
- **fsync makes writes about 12x slower.** With `FSYNC=true` every write waits
  until the disk confirms it. Writes on a node are serialized by the store
  lock, so each fsync also blocks the writes queued behind it.

## Possible improvements

- Send the writes to the replicas in parallel instead of one after the other.
- Group commit: collect the writes that arrive within a few milliseconds and
  fsync them together, so the cost of one fsync is shared by many writes.
- Run the benchmark with the client and every node on separate machines to
  get realistic absolute numbers.
