# Custom ScyllaDB image for ECS Fargate (single-task / shared network namespace).
#
# In one Fargate task all containers share localhost, so ScyllaDB's bundled
# scylla-jmx (a Java helper) collides with Cassandra on JMX port 7199 and
# crash-loops ("Port already in use: 7199"). We do not use scylla-jmx (the
# health check uses cqlsh, not nodetool), so we disable it by removing its
# supervisord program definition. The ScyllaDB engine (scylla-server) is a
# separate supervised process and is unaffected.
#
#   docker build -f ecs/scylla/Dockerfile -t <ecr>/bench/scylla:5.4 .
FROM scylladb/scylla:5.4
RUN rm -f /etc/supervisord.conf.d/scylla-jmx.conf
