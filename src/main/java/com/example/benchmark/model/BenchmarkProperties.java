package com.example.benchmark.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {

    private Jdbc mysql      = new Jdbc();
    private Jdbc postgresql = new Jdbc();
    private Jdbc cockroachdb = new Jdbc();
    private Cql  cassandra  = new Cql();
    private Cql  scylladb   = new Cql();
    private RocksDb rocksdb = new RocksDb();
    /** Comma-separated DbEngine names to run. Empty = all available. */
    private List<String> enabledEngines = new ArrayList<>();
    /** worker: run benchmark then upload to S3 and exit. orchestrator: launch ECS tasks and aggregate. */
    private String mode = "worker";
    private int rowCount = 5000;
    private int readIter = 1000;
    private S3Config s3  = new S3Config();
    private EcsConfig ecs = new EcsConfig();

    public static class S3Config {
        private String bucket    = "";
        private String runId     = "";
        private String workerKey = "";
        public String getBucket()      { return bucket; }
        public void   setBucket(String v)    { bucket = v; }
        public String getRunId()       { return runId; }
        public void   setRunId(String v)     { runId = v; }
        public String getWorkerKey()   { return workerKey; }
        public void   setWorkerKey(String v) { workerKey = v; }
    }

    public static class EcsConfig {
        private String cluster          = "db-benchmark";
        private String subnets          = "";
        private String securityGroups   = "";
        private String executionRoleArn = "arn:aws:iam::074213351472:role/ecsTaskExecutionRole";
        private String workerTaskRoleArn = "";
        public String getCluster()             { return cluster; }
        public void   setCluster(String v)     { cluster = v; }
        public String getSubnets()             { return subnets; }
        public void   setSubnets(String v)     { subnets = v; }
        public String getSecurityGroups()      { return securityGroups; }
        public void   setSecurityGroups(String v) { securityGroups = v; }
        public String getExecutionRoleArn()    { return executionRoleArn; }
        public void   setExecutionRoleArn(String v) { executionRoleArn = v; }
        public String getWorkerTaskRoleArn()   { return workerTaskRoleArn; }
        public void   setWorkerTaskRoleArn(String v) { workerTaskRoleArn = v; }
    }

    public static class Jdbc {
        private String url;
        private String username;
        private String password;
        // getters / setters
        public String getUrl()      { return url; }
        public void   setUrl(String v) { url = v; }
        public String getUsername() { return username; }
        public void   setUsername(String v) { username = v; }
        public String getPassword() { return password; }
        public void   setPassword(String v) { password = v; }
    }

    public static class Cql {
        private String hosts      = "localhost";
        private int    port       = 9042;
        private String datacenter = "datacenter1";
        public String getHosts()       { return hosts; }
        public void   setHosts(String v)  { hosts = v; }
        public int    getPort()        { return port; }
        public void   setPort(int v)   { port = v; }
        public String getDatacenter()  { return datacenter; }
        public void   setDatacenter(String v) { datacenter = v; }
    }

    public static class RocksDb {
        private String path = "./rocksdb-data";
        public String getPath()       { return path; }
        public void   setPath(String v) { path = v; }
    }

    public Jdbc    getMysql()       { return mysql; }
    public void    setMysql(Jdbc v) { mysql = v; }
    public Jdbc    getPostgresql()  { return postgresql; }
    public void    setPostgresql(Jdbc v) { postgresql = v; }
    public Jdbc    getCockroachdb() { return cockroachdb; }
    public void    setCockroachdb(Jdbc v) { cockroachdb = v; }
    public Cql     getCassandra()   { return cassandra; }
    public void    setCassandra(Cql v) { cassandra = v; }
    public Cql     getScylladb()    { return scylladb; }
    public void    setScylladb(Cql v) { scylladb = v; }
    public RocksDb getRocksdb()     { return rocksdb; }
    public void    setRocksdb(RocksDb v) { rocksdb = v; }
    public List<String> getEnabledEngines()               { return enabledEngines; }
    public void         setEnabledEngines(List<String> v) { enabledEngines = v; }
    public String    getMode()              { return mode; }
    public void      setMode(String v)      { mode = v; }
    public int       getRowCount()          { return rowCount; }
    public void      setRowCount(int v)     { rowCount = v; }
    public int       getReadIter()          { return readIter; }
    public void      setReadIter(int v)     { readIter = v; }
    public S3Config  getS3()                { return s3; }
    public void      setS3(S3Config v)      { s3 = v; }
    public EcsConfig getEcs()               { return ecs; }
    public void      setEcs(EcsConfig v)    { ecs = v; }
}
