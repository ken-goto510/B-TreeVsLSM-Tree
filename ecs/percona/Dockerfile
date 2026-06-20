# Custom Percona image for ECS Fargate.
#
# On Fargate there are no host bind mounts, so the MyRocks config that
# docker-compose mounts at /etc/mysql/conf.d/myrocks.cnf must be baked into the
# image instead. The init SQL (mysql/init/01-schema.sql) is a no-op (comments
# only) and the database is created via the MYSQL_DATABASE env var, so it is
# not needed here.
#
# Build context = repository root (so the COPY path resolves):
#   docker build -f ecs/percona/Dockerfile -t <ecr>/bench/percona:8.0 .
FROM percona/percona-server:8.0
COPY mysql/conf.d/myrocks.cnf /etc/mysql/conf.d/myrocks.cnf
