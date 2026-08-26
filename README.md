# Distributed Key-Value Store

A distributed key-value storage system built from scratch in Java to explore
distributed systems, concurrency, networking, partitioning, replication,
fault tolerance, and system design.

## Project Status

Under active development

This project is being built incrementally, starting with a single-node
key-value store and gradually evolving into a distributed system.

## Goals

- Build a key-value storage engine from scratch
- Understand client-server communication
- Explore Java concurrency and multithreading
- Implement data partitioning using consistent hashing
- Implement data replication
- Handle node failures and recovery
- Benchmark system performance
- Containerize and run a multi-node cluster
- Build observability tools for monitoring the cluster

## Planned Architecture

The system will gradually evolve from:

```text
Client
  |
  v
Single KV Server
  |
  v
In-Memory Storage
```

into:

```text
                         Client
                           |
                           v
                         Router
                           |
             +-------------+-------------+
             |             |             |
             v             v             v
          Node 1        Node 2        Node 3
             |             |             |
             +-------------+-------------+
                           |
                    Partitioning
                    + Replication
                           |
                  Fault Detection
                    + Recovery
```

## Roadmap

- [ ] Basic in-memory key-value store
- [ ] HTTP API
- [ ] Concurrent request handling
- [ ] Persistent storage
- [ ] Multi-node architecture
- [ ] Consistent hashing
- [ ] Data replication
- [ ] Failure detection
- [ ] Node recovery
- [ ] Performance benchmarking
- [ ] Dockerized cluster
- [ ] Monitoring dashboard
- [ ] Documentation and design analysis

## Technology

- Java
- Maven / Gradle
- JUnit
- Git / GitHub
- Docker
- Next.js (planned monitoring dashboard)

## Learning Focus

This project is being developed incrementally with a focus on understanding
the underlying concepts and design decisions rather than simply implementing
a final system.

## License

MIT License - see [LICENSE](LICENSE) file for details.
