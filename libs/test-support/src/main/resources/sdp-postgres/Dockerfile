# PostgreSQL image used by integration tests: same major version and partitioning extension as Azure
# Database for PostgreSQL Flexible Server (PostgreSQL 18, pg_partman 5.x).
FROM docker.io/library/postgres:18
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-18-partman \
 && rm -rf /var/lib/apt/lists/*
